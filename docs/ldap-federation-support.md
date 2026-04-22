# Adding LDAP Federation Import Support to mitodl/keycloak-scim

Summary of what it would take to make [mitodl/keycloak-scim](https://github.com/mitodl/keycloak-scim)
emit outbound SCIM events for users that Keycloak imports via its LDAP User Federation,
so OD360's SCIM server sees third-party-managed users without OD360 having to poll
LDAP or Keycloak directly.

## Problem

mitodl's `ScimEventListenerProvider` (in `src/main/java/sh/libre/scim/event/`) subscribes
only to Keycloak's `EventListenerProvider` SPI. That SPI fires for:

- end-user actions (registration, profile self-update, email verify, delete-account)
- admin console / admin REST API actions (user/group CRUD, role mappings)

It does **not** fire for any path by which Keycloak's LDAP User Federation imports users
from an LDAP backend (lazy on-demand lookup, the periodic sync job, or an explicit
`/user-storage/{id}/sync` call). This is a Keycloak-core limitation, documented in
[*Keycloak: Event Listener SPI for LDAP / User Federation Sync*](https://medium.com/@ivancheahkf/keycloak-event-listener-spi-for-ldap-user-federation-sync-62fa17c573bc)
and confirmed by reading mitodl's source — no `LDAPStorageMapper` subclass exists anywhere
in the repository.

Consequence: in any deployment where users originate in LDAP (FreeIPA) and flow into
Keycloak only via federation, mitodl is silent. No SCIM POST ever reaches OD360 for the
initial import, and no SCIM PATCH reaches OD360 for subsequent LDAP-driven updates.
Users only produce outbound SCIM traffic once an admin touches them in the Keycloak
admin console — which for third-party-managed deployments is rare to never.

## The hook that *does* cover federation imports

`org.keycloak.storage.ldap.mappers.LDAPStorageMapper#onImportUserFromLDAP(LDAPObject, UserModel, RealmModel, boolean isCreate)`.

This method fires on every LDAP-to-Keycloak user materialization, across all three
federation trigger paths:

| Trigger | Fires `onImportUserFromLDAP`? |
| --- | --- |
| Lazy on-demand lookup (e.g., `GET /users?username=foo` triggers a federation fetch) | yes |
| Periodic sync (`triggerChangedUsersSync` scheduled) | yes |
| Explicit `POST /user-storage/{id}/sync` | yes |

The `isCreate` argument distinguishes a first-time import from an update.

## Required changes to mitodl

### 1. New package: `sh.libre.scim.ldap` (or `sh.libre.scim.storage.ldap`)

Two new classes:

#### `ScimLdapStorageMapperFactory implements LDAPStorageMapperFactory<ScimLdapStorageMapper>`

- Registers the mapper so it appears in the Keycloak admin UI as an assignable mapper
  on each LDAP Storage Provider.
- `getId()` → a stable string like `"scim-ldap-sync"`.
- `create(session, model)` → returns a new `ScimLdapStorageMapper`.
- Declare configurable properties on the factory if you want per-provider knobs
  (e.g., "only propagate users carrying role X", "dry-run mode"); otherwise leave
  config empty and inherit from the event-listener-level config.

#### `ScimLdapStorageMapper implements LDAPStorageMapper`

Only `onImportUserFromLDAP` does real work. The other methods can no-op (see the Medium
article for the skeleton):

```java
@Override
public void onImportUserFromLDAP(LDAPObject ldapUser, UserModel user, RealmModel realm, boolean isCreate) {
    // Reuse mitodl's existing ScimClient / ScimResource machinery.
    // Resolve the configured SCIM service providers for this realm via the
    // same UserStorageProviderFactory mechanism the event listener uses.
    // For each provider:
    //   if (isCreate) scimClient.create(user);
    //   else          scimClient.replace(user);
    // Persist mitodl's ScimResource row (ssoId ↔ remote SCIM id) via the
    // existing JpaEntityProvider so subsequent updates hit the right endpoint.
}

@Override public void close() {}
@Override public void onRegisterUserToLDAP(LDAPObject ldapUser, UserModel localUser, RealmModel realm) {}
@Override public LDAPStorageProvider getLdapProvider() { return null; }
@Override public boolean onAuthenticationFailure(LDAPObject ldapUser, UserModel user, AuthenticationException ex, RealmModel realm) { return false; }
@Override public void beforeLDAPQuery(LDAPQuery query) {}
@Override public UserModel proxy(LDAPObject ldapUser, UserModel delegate, RealmModel realm) { return delegate; }
@Override public List<UserModel> getRoleMembers(RealmModel realm, RoleModel role, int firstResult, int maxResults) { return null; }
@Override public List<UserModel> getGroupMembers(RealmModel realm, GroupModel group, int firstResult, int maxResults) { return null; }
@Override public SynchronizationResult syncDataFromFederationProviderToKeycloak(RealmModel realm) { return new SynchronizationResult(); }
@Override public SynchronizationResult syncDataFromKeycloakToFederationProvider(RealmModel realm) { return new SynchronizationResult(); }
```

The body of `onImportUserFromLDAP` should delegate to mitodl's existing SCIM outbound
code paths so the mapper doesn't duplicate HTTP client, auth, retry, or mapping logic.
Today that code lives under `sh/libre/scim/core/` and is invoked from
`ScimEventListenerProvider` — extract a shared method or service and call it from both
entry points.

### 2. Maven dependency

Add to `pom.xml`:

```xml
<dependency>
  <groupId>org.keycloak</groupId>
  <artifactId>keycloak-ldap-federation</artifactId>
  <version>${keycloak.version}</version>
  <scope>provided</scope>
</dependency>
```

### 3. Deployment descriptor (Keycloak on WildFly only)

If the plugin is being deployed to a legacy WildFly-based Keycloak, add to
`jboss-deployment-structure.xml`:

```xml
<module name="org.keycloak.keycloak-ldap-federation" />
```

Quarkus-based Keycloak (the default since Keycloak 17+) does not need this.

### 4. Service registration

Create `src/main/resources/META-INF/services/org.keycloak.storage.ldap.mappers.LDAPStorageMapperFactory`
containing the fully-qualified class name:

```
sh.libre.scim.ldap.ScimLdapStorageMapperFactory
```

### 5. Realm-level configuration (operator step, not code)

After deployment, an operator must attach the new mapper to each LDAP Storage Provider
instance in each realm via the Keycloak admin UI: *User Federation → (LDAP provider) →
Mappers → Create → SCIM LDAP Sync*. The mapper is per-provider, per-realm. Document this
in the plugin's README.

## Architecture notes

### Routing to the correct SCIM client

mitodl represents each configured external SCIM service provider as a
`UserStorageProviderFactory` instance (it reuses Keycloak's UserStorageProvider SPI as
a configuration-UI container, not as an actual user-storage mechanism). The new
LDAP mapper needs access to the same list. Two options:

- **Shared service**: extract a `ScimPropagation` helper from
  `ScimEventListenerProvider` that takes `(KeycloakSession, RealmModel, UserModel, Op)`
  and fans out to every configured SCIM provider. Both the event listener and the LDAP
  mapper call this helper.
- **Queue-based**: write a `PendingScimOp` row via the existing `JpaEntityProvider`
  (`ScimResource` schema) and let a background worker drain the queue. Decouples the
  import path from HTTP latency. Heavier, but removes the "event listener is not
  cancelable" failure-semantics problem mitodl's README already calls out.

The shared-service option is smaller and ships faster. The queue option is the right
long-term shape if you find that federation sync batches are large and blocking SCIM
calls are causing sync timeouts on the Keycloak side.

### Create vs update

`isCreate == true` → SCIM POST. `isCreate == false` → SCIM PATCH (or PUT, depending on
how mitodl's core already handles update). Reuse whatever mapping mitodl's event listener
uses for the `UPDATE` admin event; the data shape is identical.

### Deletion

`LDAPStorageMapper` does **not** have an `onDelete` / `onRemove` hook. When an LDAP
entry disappears and the Keycloak periodic sync detects it, Keycloak removes the local
`UserModel`. That removal is an admin event — so in principle mitodl's existing
`ScimEventListenerProvider` catches it via the admin `USER DELETE` event. **Verify this
empirically in the spike**: it is plausible that removals originating from
`LDAPStorageProvider.removeNonExistentUsers()` go through a different code path that
bypasses the admin event fabric. If they do, you need to supplement the mapper with
logic that reconciles deletions — either by diffing OD360's SCIM resource list against
the current Keycloak user set on a timer, or by hooking
`syncDataFromFederationProviderToKeycloak` and comparing before/after.

### Prerequisites on the Keycloak side

- The LDAP Storage Provider **must** have `Import Users = ON`. If it's OFF, federated
  users never get a `UserModel` row and `onImportUserFromLDAP` never fires. Document
  this as a hard requirement.
- The configured LDAP sync schedule determines update latency. Lazy-import covers
  first-authentication; for changes originating in LDAP between logins, periodic sync
  is the only trigger. Consider recommending a short `Changed Users Sync Period`
  (default 24h) for third-party deployments.

### Idempotency

`onImportUserFromLDAP` can fire more than once for the same user (e.g., lazy import,
then periodic sync). The outbound SCIM operation must be idempotent. mitodl's existing
`ScimResource` table stores the local-to-remote id mapping; the propagation helper
should use this to pick POST vs PATCH regardless of the `isCreate` flag. Trust the
local database over the hook argument.

### Failure handling

mitodl's README already notes that its event listener is "not cancelable" — if the
downstream SCIM server fails, there's no clean way to roll back the Keycloak state.
The LDAP mapper inherits the same constraint: `onImportUserFromLDAP` is called inside
Keycloak's LDAP sync transaction, and throwing will abort the import of that user.
Decide:

- **Fail-closed**: throw on SCIM errors. The user is not imported into Keycloak, so
  authentication fails until OD360 is reachable. Matches the "complete record or
  nothing" posture we adopted with the provisioning-disable tail sweep.
- **Fail-open with queueing**: catch, enqueue a pending SCIM op, let a retry worker
  drain it. User is imported into Keycloak immediately; OD360 catches up shortly.

Fail-open is almost certainly what you want for third-party deployments — you don't
want a transient OD360 outage to make every federated user fail to authenticate into
Keycloak. Pairs with the queue-based architecture option above.

## Testing

Set up a `docker-compose.yml` with: Keycloak, a FreeIPA (or OpenLDAP) container, and a
lightweight SCIM sink (e.g., a small Express app that logs every request). Configure
Keycloak's LDAP User Federation against the FreeIPA/OpenLDAP with `Import Users = ON`
and the new mapper attached. Then exercise:

1. Create a user directly in LDAP → trigger lazy import via Keycloak admin API
   (`GET /users?username=foo`) → assert SCIM POST at the sink.
2. Modify the user in LDAP → `triggerChangedUsersSync` → assert SCIM PATCH at the sink
   with the `isCreate=false` path.
3. `triggerFullSync` with a large user cohort → assert one SCIM POST/PATCH per imported
   user, no duplicates.
4. Delete the user in LDAP → `triggerChangedUsersSync` → assert either (a) a SCIM DELETE
   fires via the admin-event path mitodl already handles, or (b) document the gap and
   plan the deletion-reconciliation workaround.
5. Kill the SCIM sink mid-sync → verify the chosen failure semantics (fail-closed vs
   fail-open) behaves as designed.

## Open questions

- **Does scim-for-keycloak's commercial build hook `LDAPStorageMapper`?** If yes, this
  whole doc is moot for deployments that standardize on scim-for-keycloak; the plugin
  already does it. Ask their support: *"Does outbound SCIM fire when users are imported
  by Keycloak's LDAP User Federation, across lazy/periodic/explicit triggers?"*
- **Upstream acceptance.** If we build this, is mitodl interested in taking the PR?
  Worth asking before forking — maintaining a fork for something this central is
  expensive across Keycloak version upgrades.
- **Keycloak version compatibility.** `LDAPStorageMapper` has had signature changes
  across Keycloak majors. Pin the target version range explicitly in the PR.
- **Role-gating parity with scim-for-keycloak.** scim-for-keycloak uses a
  `scim-managed` realm role as an opt-in filter for which users participate in SCIM.
  If we want the same safety net in mitodl, the mapper should check for the role
  before emitting. Decide whether to implement that here or leave it for a follow-up.

## References

- [mitodl/keycloak-scim README](https://github.com/mitodl/keycloak-scim/blob/main/README.md)
- [Medium: Keycloak Event Listener SPI for LDAP / User Federation Sync](https://medium.com/@ivancheahkf/keycloak-event-listener-spi-for-ldap-user-federation-sync-62fa17c573bc)
  — the tutorial that walks through `LDAPStorageMapper` end-to-end, including the
  `org.keycloak.storage.ldap.mappers.LDAPStorageMapperFactory` META-INF registration
  file and the admin-UI attachment step.
- [Keycloak forum: Event SPI and Users added from other sources](https://forum.keycloak.org/t/event-spi-and-users-added-from-other-sources-identity-provider-federated-provider/9433)
  — original discussion establishing that EventListenerProvider does not fire for
  federation-origin users.
- [Captain-P-Goldfish/scim-for-keycloak issue #92](https://github.com/Captain-P-Goldfish/scim-for-keycloak/issues/92)
  — LDAP-federated users in SCIM server responses; confirms federated users can be
  surfaced via SCIM, just not necessarily auto-propagated outbound.

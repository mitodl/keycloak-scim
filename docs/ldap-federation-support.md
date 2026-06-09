# Adding LDAP Federation Import Support to mitodl/keycloak-scim

Summary of what it would take to make [mitodl/keycloak-scim](https://github.com/mitodl/keycloak-scim)
emit outbound SCIM events for users that Keycloak imports via its LDAP User Federation,
so a downstream SCIM service provider sees third-party-managed users without
having to poll LDAP or Keycloak directly.

## Status (2026-04-23)

**Shipped**

- `sh.libre.scim.ldap.ScimLdapStorageMapper` and `ScimLdapStorageMapperFactory`
  (id `scim-ldap-sync`), with `META-INF/services` registration.
- `keycloak-ldap-federation` as a `compileOnly` dep via the Gradle version catalog;
  targets Keycloak 25.0.6 minimum, binary-compatible with 26.x via a single artifact.
- Shared-service routing: the mapper delegates to the existing `ScimDispatcher`
  so the event-listener path and the federation-import path fan out to the same
  configured SCIM providers.
- Fail-open failure semantics: SCIM errors are caught in `ScimDispatcher.runOne`
  and do not abort the Keycloak-side LDAP import.
- Idempotency: `ScimClient.create` short-circuits when a `ScimResource` mapping
  already exists locally, so lazy-imports and repeated syncs do not produce
  duplicate SCIM resources.
- Test harness: JUnit 5 + Mockito unit tests plus Testcontainers-driven
  integration tests (Keycloak + osixia/openldap + embedded WireMock SCIM sink).
  Covers scenarios 1 (lazy import → POST), 2 (modify in LDAP +
  `triggerChangedUsersSync` → PUT via the `isCreate=false` path), 3 (full
  sync → one POST per user, no duplicates), and 5 (fail-open on SCIM sink
  failure). Also covers the admin-REST event-listener path end-to-end:
  admin create → POST, admin update → PUT, admin delete → DELETE, and
  `username-source=email` emitting the email as the SCIM userName.
- Fixed a pre-existing NPE in `ScimEventListenerProvider`'s DELETE
  handler: it was calling `getUser(userId)` post-commit (after the user
  had already been deleted), then dereferencing `user.isEmailVerified()`.
  Now uses `event.getUserId()` directly. The mapping table is authoritative
  for whether a user was ever propagated; the `emailVerified` gate at
  delete time was redundant.

**Deferred / open**
- Scenario 4 (deletion reconciliation) — **scoped solution shipped.**
  Baseline behavior (no reconciler running) is still pinned by
  `ldapSyncAloneDoesNotPropagateDeletion`: removing a user from LDAP +
  `triggerFullSync` does not propagate to SCIM, because upstream
  Keycloak [#35235](https://github.com/keycloak/keycloak/issues/35235)
  leaves the local `UserModel` in place and no admin `USER/DELETE`
  event fires. The plugin now ships a reconciler
  (`ReconcilerRunner` + `POST /realms/{realm}/scim-reconcile/{componentId}`)
  that issues SCIM DELETE for mapping rows whose local user is gone or
  whose `ldap-federation-last-seen` attribute is stale past a
  configurable threshold. Verified end-to-end by
  `reconcilerDeletesScimResourcesForMissingLdapUsers`.
- Role-gating: opt-in filter to restrict SCIM propagation to a configurable subset of users.

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
Keycloak only via federation, mitodl is silent. No SCIM POST ever reaches the downstream
SCIM server for the initial import, and no SCIM PATCH reaches it for subsequent
LDAP-driven updates. Users only produce outbound SCIM traffic once an admin touches
them in the Keycloak admin console — which for third-party-managed deployments is
rare to never.

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
logic that reconciles deletions — either by diffing the downstream SCIM server's
resource list against the current Keycloak user set on a timer, or by hooking
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
  authentication fails until the SCIM server is reachable. Matches a "complete
  record or nothing" posture.
- **Fail-open with queueing**: catch, enqueue a pending SCIM op, let a retry worker
  drain it. User is imported into Keycloak immediately; the SCIM server catches up
  shortly.

Fail-open is almost certainly what you want for third-party deployments — you don't
want a transient SCIM-server outage to make every federated user fail to authenticate
into Keycloak. Pairs with the queue-based architecture option above.

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

- **Keycloak version compatibility.** `LDAPStorageMapper` has had
  signature changes across Keycloak majors. The current build targets
  Keycloak 25.0.6 minimum and is binary-compatible with 26.x. Future
  Keycloak majors will need verification, and the test harness's
  Testcontainers setup gives us a fast path to do that.
- **Role-gating.** Add an opt-in filter so the mapper only propagates users that carry
  a configured realm role. Decide whether to implement in the mapper or as a follow-up.

## Reconciler design (v1 shipped; extensions planned)

Closes the scenario-4 gap: LDAP-federated users who disappear from
LDAP stay in Keycloak (upstream bug
[#35235](https://github.com/keycloak/keycloak/issues/35235)) and
therefore stay in the external SCIM sink. The reconciler periodically
issues SCIM DELETEs for mappings whose federation-backed user hasn't
been observed from LDAP in a configured window.

### Principles

- **Scope is outbound SCIM only.** We do not delete the local
  `UserModel`; that's Keycloak's responsibility, tracked in #35235.
  When upstream fixes it, admin `USER/DELETE` events fire and our
  existing event-listener path propagates the delete — no change
  required to the reconciler.
- **Opt-in, default off.** Operators on affected Keycloak versions
  enable it explicitly; on versions where upstream has fixed the bug,
  leaving it off (or even on, idempotently) is fine.
- **Uses only public SPIs.** No Hibernate integrators, no private
  subclassing. `TimerProvider` for scheduling, `session.users()` /
  `UserModel.getFederationLink()` for data access, the existing
  `ScimDispatcher` / `ScimClient.delete` for the outbound call.
- **Must be idempotent with the event-listener path.** Both can fire
  for the same delete; the net effect must be one SCIM DELETE and one
  cleared mapping. `ScimClient.delete` already handles the "mapping
  missing" case via `NoResultException`, so concurrent races are safe.

### Liveness signal: `ldap-federation-last-seen`

Every `ScimLdapStorageMapper.onImportUserFromLDAP` invocation sets the
`ldap-federation-last-seen` user attribute to the current timestamp.
This covers lazy imports, periodic sync, and explicit
`triggerFullSync` / `triggerChangedUsersSync` triggers — the same
three paths our primary mapper hook covers. Attribute name is
federation-type-specific (not SCIM-specific) so the same signal is
reusable by future federation integrations.

Storage: user attributes are persisted via Keycloak's existing
`USER_ATTRIBUTE` table. No plugin-side schema migration.

### Decision rule (pluggable witnesses)

The reconciler evaluates each federation-linked user against one or
more witnesses, framed as independent evidence. **Delete only when all
active witnesses agree the user is absent.** Today there is one
witness; the design leaves room for more without restructuring.

Active witnesses at v1:

- **Timestamp witness:** `ldap-federation-last-seen` older than
  `reconciler-stale-threshold-hours`. Authoritative for "stale."

In addition, before witnesses are consulted, the reconciler performs
two preconditions on each mapping row:

1. `session.users().getUserById(realm, mappingId)` — if `null`, the
   local user is gone entirely (federation dropped it, admin deleted
   it, etc.); delete the SCIM resource without consulting witnesses.
2. Otherwise check `user.getFederationLink()` — if `null`, the user is
   local-only and out of scope for the LDAP reconciler; skip.

The timestamp witness only applies to users that still exist locally
and still have a federation link but haven't been observed recently.

Planned witnesses (future, not v1):

- **Bloom-filter witness:** during sync, every
  `onImportUserFromLDAP` call adds the username to an in-progress
  Bloom filter scoped to the LDAP federation component. When the
  reconciler considers a candidate, if the filter is fresh (its
  `built_at` is within 2× the federation's sync period) and
  `filter.mightContain(username)` is `false`, the witness votes
  "absent." If the filter is stale, the witness abstains and the
  decision falls back to timestamps alone (degrades gracefully).
  Catches a narrow class of bug — silent timestamp-write failures
  where the threshold buffer doesn't help — at modest cost
  (~1–2 bytes/user; one `put` per user per sync).

Why this shape: it gives a clear extension point if we observe false
positives in production, without making the v1 implementation more
complex than it needs to be. Retrofitting the filter later means
adding a witness impl and including it in the AND, not restructuring
the reconciler.

### Config (shipped on the SCIM provider component)

- `reconciler-enabled` (bool, default `false`)
- `reconciler-interval-seconds` (string, default `"86400"` — 24h)
- `reconciler-stale-threshold-seconds` (string, default `"172800"` — 48h)

Values in seconds to match Keycloak's own federation-sync config
convention (e.g. `fullSyncPeriod`). The endpoint also accepts a
`thresholdHours` query param for operator-forced passes.

Config validation at component save time (shipped, in
`ReconcilerConfigValidator`):

- When `reconciler-enabled=false`, no validation runs (operator hasn't
  opted in).
- Both `reconciler-interval-seconds` and
  `reconciler-stale-threshold-seconds` must be positive.
- `reconciler-stale-threshold-seconds` must be strictly greater than
  `reconciler-interval-seconds`.
- For every LDAP federation in the realm with `fullSyncPeriod > 0`,
  `reconciler-stale-threshold-seconds` must be strictly greater than
  that federation's `fullSyncPeriod` — otherwise the reconciler would
  delete users the federation simply hasn't had time to re-observe.
  Federations with periodic sync disabled (`fullSyncPeriod <= 0`) are
  skipped from this check; operators who disable periodic sync are
  accepting that the reconciler operates on lazy-import-only liveness
  data.

Violations throw `ComponentValidationException` at save time, so the
admin console / REST surfaces the error and the bad config never
reaches the timer.

### Manual trigger (shipped)

A convenience endpoint via `RealmResourceProviderFactory`:
`POST /realms/{realm}/scim-reconcile/{componentId}` forces an
immediate reconciliation pass for the given SCIM provider, without
waiting for the timer. Optional `thresholdHours` query param overrides
the default for a single call (useful for operator-forced cleanups).
Returns `{"deleted": N}`.

### Scheduled trigger (shipped)

`ScimStorageProviderFactory` schedules a `TimerProvider` task per SCIM
component when `reconciler-enabled=true`. Entry points:
- `onCreate` fires when a component is added to a realm via the admin
  console / REST, so operators who configure a new SCIM provider get
  their timer scheduled without a restart.
- `onUpdate` reschedules on config changes (interval, threshold,
  enabled flag flipping).
- `preRemove` cancels the timer when the component is deleted.
- `postInit` does a boot-time scan so existing components get their
  timers back after a Keycloak restart.

The scheduled task runs in its own `KeycloakSession` via
`KeycloakModelUtils.runJobInTransaction`. Because `ScimClient.delete`
is idempotent (SCIM DELETE on a missing mapping is a no-op), clustered
deployments where every Keycloak node schedules its own timer are safe
— just slightly wasteful. Config knob to deduplicate cluster-wide is
a potential follow-up.

### If upstream Keycloak ever fixes #35235

The pattern this reconciler implements (timestamp-based liveness +
threshold) maps cleanly to the `TODO` in Keycloak's own
`LDAPStorageProviderFactory.sync`: "*Remove all existing keycloak
users, which have federation links, but are not in LDAP. Perhaps
don't check users, which were just added or updated during this
sync?*" — the parenthetical is exactly the threshold logic.

If Keycloak eventually adopts this pattern (e.g. a
`FEDERATION_LAST_SEEN_AT` column on `USER_ENTITY` plus a scheduled
task that deletes local users past a configured threshold), the
plugin-side reconciler self-deactivates: Keycloak's task fires admin
`USER/DELETE` events, our existing event listener catches them,
mapping rows get cleared via the `delete()` path, and nothing remains
for our reconciler to find on its next tick. No version detection
needed; works on every supported Keycloak release.

## Group-membership propagation (shipped)

When `onImportUserFromLDAP` fires, `ScimLdapStorageMapper` now also
propagates the imported user's current group memberships to SCIM via
`ScimClient.ensureGroupMembership`. For each group the user belongs
to at import time, the call sequence is:

1. **Ensure the group exists in SCIM.** `ensureGroupMembership` issues
   an idempotent group create (short-circuits when a local mapping
   already exists). When the SCIM provider component has
   `group-patchOp=false` this step is skipped — the `replace` path
   exercised during a normal group sync already covers it.
2. **Add the member via delta PATCH.** A single-member ADD PATCH
   (RFC 7644) is sent for the user. This is the same delta-PATCH
   mechanism used by the `GROUP_MEMBERSHIP` event-listener path.

This covers additions only. Membership is re-asserted on every import,
so periodic and full syncs self-heal any drift.

### Operator requirement

The SCIM provider component must have **both** `propagation-user=true`
and `propagation-group=true` enabled. Membership resolution looks up
the user's SCIM resource id under the same component, so a component
configured for group propagation alone cannot resolve member ids and
will skip group-membership propagation silently. A single component
covering both scopes is the supported configuration.

### Lazy-import convergence

On a one-shot lazy import (a single user login triggers federation
fetch), the group-membership task may run before the user's SCIM
mapping has been persisted and skip. This is the expected behaviour:
the next periodic sync re-asserts all memberships, so the state
converges without operator intervention. The accepted lag is one sync
interval.

### Not yet handled

- **Membership removal.** When a user is dropped from an LDAP group,
  the removal is not propagated to SCIM. This is a deferred
  reconciler-style follow-up (analogous to the user-deletion gap
  tracked in scenario 4 above).
- **Group rename and delete for federated groups.** Name changes and
  deletions of groups that originate in LDAP are not yet propagated.

## References

- [mitodl/keycloak-scim README](https://github.com/mitodl/keycloak-scim/blob/main/README.md)
- [Medium: Keycloak Event Listener SPI for LDAP / User Federation Sync](https://medium.com/@ivancheahkf/keycloak-event-listener-spi-for-ldap-user-federation-sync-62fa17c573bc)
  — the tutorial that walks through `LDAPStorageMapper` end-to-end, including the
  `org.keycloak.storage.ldap.mappers.LDAPStorageMapperFactory` META-INF registration
  file and the admin-UI attachment step.
- [Keycloak forum: Event SPI and Users added from other sources](https://forum.keycloak.org/t/event-spi-and-users-added-from-other-sources-identity-provider-federated-provider/9433)
  — original discussion establishing that EventListenerProvider does not fire for
  federation-origin users.

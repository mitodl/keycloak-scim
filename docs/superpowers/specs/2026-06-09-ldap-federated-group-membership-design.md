# LDAP-Federated Group Membership Propagation — Design

## Problem

When a user is materialized from LDAP User Federation,
`ScimLdapStorageMapper.onImportUserFromLDAP` propagates the **user**
record to SCIM (POST on create, PUT on replace). It does **not**
propagate that user's **group memberships**. So a user federated from
LDAP shows up in the downstream SCIM service, but is not a member of
any of their LDAP groups there.

The root cause is the same Keycloak limitation that motivated the LDAP
mapper in the first place: LDAP-driven membership changes never fire
`GROUP_MEMBERSHIP` admin events, so the event-listener path
(`ScimEventListenerProvider`) is silent for them. The only signal we
get is `onImportUserFromLDAP`, which fires on every materialization
(lazy login, periodic sync, full sync) but carries no membership delta
— just the user.

## Goal

For every federated-user import, ensure the user's **current** group
memberships are reflected in SCIM: each group the user belongs to
exists as a SCIM group, and the user is a member of it. Idempotent, so
re-asserting on every import (the only way to catch memberships added
in LDAP between syncs) is safe and cheap.

## Decisions

1. **Additions only; removals deferred.** v1 ensures *current*
   memberships are present. Detecting that a user has *left* an LDAP
   group (no event carries this) is a reconciler-style follow-up — a
   "membership liveness" pass — mirroring how user-deletion
   reconciliation was handled. Out of scope here.

2. **Ensure the group exists before adding the member.** A federated
   group may not yet be provisioned to SCIM (LDAP-federated groups
   don't fire `GROUP` create events either). `patchGroupMembership`
   bails with `NoResultException` when the group mapping is absent
   (`group-patchOp=true` path does `findById(groupId)` first), so an
   explicit ensure-group step must precede the member-add or the add
   silently no-ops.

3. **Re-assert on every import, not just `isCreate`.** Acting only on
   first import would reintroduce the staleness gap: a user added to a
   new LDAP group after their initial import would never propagate.
   Each op is individually idempotent, so every-import re-assertion is
   safe; cost is bounded (see Scaling).

4. **Act on all current memberships, no federated-vs-local filtering.**
   `GroupModel` has no clean federation-link accessor like
   `UserModel.getFederationLink()`, so filtering to LDAP-originated
   groups would require brittle group-attribute/mapper sniffing for the
   sole benefit of skipping idempotent no-ops on local groups (which
   already propagate via the admin event path). Treating "propagate all
   of the imported user's current memberships" uniformly is simpler and
   robust.

5. **Accept eventual consistency; no cross-scope coordination.** See
   Ordering below. Matches the plugin's existing fail-open,
   eventually-consistent posture.

## Architecture

### Trigger

Extend `ScimLdapStorageMapper.onImportUserFromLDAP`. After the existing
`SCOPE_USER` create/replace dispatch, add a second
`dispatcher.runAsync(SCOPE_GROUP, ...)` task that propagates the user's
group memberships. No new Keycloak hook — the existing hook already
covers all three federation trigger paths.

Group IDs are captured by value (like the existing code captures
`userId`); the worker re-fetches the user and groups in its own session
post-commit, consistent with the existing async contract.

### The propagation unit

A new `ScimClient` method composed from existing, individually
idempotent pieces:

```
ensureGroupMembership(groupFactory, groupId, userId):
    1. ensure the SCIM group exists
         reuse create(GroupAdapter::new, group)
         — short-circuits when a local ScimResource mapping already exists
    2. add the member
         reuse patchGroupMembership(GroupAdapter::new, groupId, userId, isAdd=true)
         — single-member delta PATCH, idempotent
```

Step 1 is the precondition for step 2 (decision 2). Both are
idempotent, so the every-import re-assertion (decision 3) is a no-op
after the first time for a stable membership.

### Scaling

Step 1's `create` serializes the group's **full** member list the
*first* time a given group is provisioned (that is how `GroupAdapter`
renders a group). After that the local mapping exists and `create`
short-circuits, so subsequent users importing into the same group cost
only the single-member delta PATCH. The full-list send is
one-time-per-group, not per-user — it does not reintroduce the
per-membership-change full-list cost that the membership delta PATCH
work removed.

### Ordering and consistency

`patchGroupMembership` (running on a `SCOPE_GROUP` component) resolves
the member's SCIM external ID by looking up the **User mapping** under
that component — written by the `SCOPE_USER` create task. Both tasks
fire post-commit, in parallel, on the shared worker pool, with no
ordering guarantee. On a first import the membership task can run
before the user mapping exists, in which case `patchGroupMembership`
hits `NoResultException` and skips.

Resolution per trigger path:

- **Periodic / full sync:** self-healing. The user mapping lands this
  cycle or a prior one; the next sync's re-assertion (decision 3) adds
  the membership. No extra machinery.
- **Lazy single-login import:** one-shot — if the membership task loses
  the race, the membership lags until the next periodic sync. Accepted
  property, documented; not worth cross-scope coordination machinery.

This is consistent with the existing reconciler / async-dispatch /
"trust the local DB over the hook" design already in the codebase.

### Failure semantics and gating

- `dispatcher.runOne` catches and logs per component; a SCIM error on
  group propagation never aborts the Keycloak import (fail-open,
  unchanged).
- A missing user or group mapping is an `infof` skip, mirroring
  `delete()` / `patchGroupMembership` today.
- Runs only for components with `propagation-group=true` (existing
  `SCOPE_GROUP` filter). A group-scope component that is not also
  user-scope cannot resolve the member's external ID — a pre-existing
  constraint of `patchGroupMembership`, inherited and documented here,
  not solved.

## Testing

### Spike to verify first

Confirm `user.getGroupsStream()` is populated at `onImportUserFromLDAP`
time. Keycloak's `group-ldap-mapper` materializes memberships via its
own mapper callback and mapper execution order is not guaranteed. The
worker re-fetches the user by ID post-commit, which reads committed
state and is the likely-sufficient path regardless; the integration
test proves it empirically.

### Unit (fast, no Docker)

- `ensureGroupMembership` wiring: ensure-group-then-add ordering; the
  ensure step short-circuits when the group mapping exists; the add
  reuses the single-member delta PATCH.
- Skip-on-missing-mapping: user mapping absent → `infof` skip, no
  throw.

### Integration (Testcontainers + OpenLDAP + WireMock)

- Federated user in an LDAP group → import → assert the SCIM group is
  created (first time) and a member-add PATCH targets it.
- Second federated user into the same group → assert group create
  short-circuits (no duplicate) and only a member-add fires.
- Re-sync of an already-propagated user → idempotent (no errors,
  membership re-asserted).

## Non-goals (deliberately deferred)

- **Membership removal** — user dropped from an LDAP group. Deferred to
  a reconciler-style "membership liveness" follow-up.
- **Group rename / delete** propagation for federated groups.
- **Federated-vs-local group filtering** (decision 4: act on all
  current memberships).
- **Group-only components** that do not also propagate users (cannot
  resolve member external IDs — pre-existing constraint).

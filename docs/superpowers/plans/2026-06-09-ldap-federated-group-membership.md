# LDAP-Federated Group Membership Propagation — Implementation Plan

> **For agentic workers:** REQUIRED: Use superpowers:subagent-driven-development (if subagents available) or superpowers:executing-plans to implement this plan. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Propagate an LDAP-federated user's current group memberships to SCIM on every import, ensuring each group exists remotely before adding the member.

**Architecture:** Add an idempotent `ScimClient.ensureGroupMembership(factory, groupId, userId)` that ensures the SCIM group exists (reusing `create`, which short-circuits on an existing mapping) and then adds the member (reusing the single-member delta `patchGroupMembership`). Dispatch it from `ScimLdapStorageMapper.onImportUserFromLDAP` as a second `runAsync(SCOPE_GROUP, …)` task, iterating the user's committed `getGroupsStream()` in the worker session.

**Tech Stack:** Java 21, Keycloak SPI (`LDAPStorageMapper`), Captain Goldfish SCIM SDK, resilience4j, JUnit 5 + Mockito (unit), Testcontainers + OpenLDAP + WireMock (integration).

**Spec:** `docs/superpowers/specs/2026-06-09-ldap-federated-group-membership-design.md`

---

## Chunk 1: `ensureGroupMembership` core method

### Task 1: Add `ensureGroupMembership` to `ScimClient`

**Files:**
- Modify: `src/main/java/sh/libre/scim/core/ScimClient.java` (add method after `patchGroupMembership`, ~line 381)
- Test: `src/test/java/sh/libre/scim/core/EnsureGroupMembershipTest.java` (create)

The new method is thin glue over two existing idempotent operations. The behavior worth pinning: (a) when `group-patchOp=true`, the ensure-group `create` runs *before* the member-add; (b) when `group-patchOp=false`, the ensure-group `create` is skipped (the `patchGroupMembership` `replace` fallback already re-sends the whole group); (c) a missing local group is skipped, not propagated. We verify this with a Mockito spy that stubs the two reused methods, so no HTTP or DB is needed — the reused methods have their own coverage (`ScimClientCreateResponseTest`, `GroupMembershipPatchTest`, `ScimGroupPropagationIT`).

- [ ] **Step 1: Write the failing test**

```java
package sh.libre.scim.core;

import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import de.captaingoldfish.scim.sdk.common.resources.Group;
import org.junit.jupiter.api.Test;
import org.keycloak.common.util.MultivaluedHashMap;
import org.keycloak.component.ComponentModel;
import org.keycloak.models.GroupModel;
import org.keycloak.models.GroupProvider;
import org.keycloak.models.KeycloakContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.mockito.InOrder;

/**
 * Pins the branching of {@link ScimClient#ensureGroupMembership}: ensure-group
 * runs before member-add when group-patchOp is on, is skipped when it is off,
 * and a missing local group short-circuits. The reused create/patch operations
 * are stubbed — their real behavior is covered by ScimClientCreateResponseTest,
 * GroupMembershipPatchTest, and the LDAP membership IT.
 */
class EnsureGroupMembershipTest {

    private ScimClient newClient(boolean groupPatchOp, GroupModel group) {
        var model = new ComponentModel();
        var config = new MultivaluedHashMap<String, String>();
        config.putSingle("auth-mode", "NONE");
        config.putSingle("endpoint", "https://scim.example/scim/v2");
        config.putSingle("content-type", "application/scim+json");
        config.putSingle("group-patchOp", Boolean.toString(groupPatchOp));
        model.setConfig(config);
        model.setId("comp-grp");

        var session = mock(KeycloakSession.class);
        var context = mock(KeycloakContext.class);
        var realm = mock(RealmModel.class);
        var groups = mock(GroupProvider.class);
        when(session.getContext()).thenReturn(context);
        when(context.getRealm()).thenReturn(realm);
        when(session.groups()).thenReturn(groups);
        when(groups.getGroupById(realm, "grp-1")).thenReturn(group);

        return new ScimClient(model, session);
    }

    @Test
    void groupPatchOpOn_ensuresGroupThenAddsMember() {
        var group = mock(GroupModel.class);
        var client = spy(newClient(true, group));
        doNothing().when(client).create(GroupAdapter::new, group);
        doNothing().when(client).patchGroupMembership(GroupAdapter::new, "grp-1", "user-1", true);

        client.ensureGroupMembership(GroupAdapter::new, "grp-1", "user-1");

        InOrder order = inOrder(client);
        order.verify(client).create(GroupAdapter::new, group);
        order.verify(client).patchGroupMembership(GroupAdapter::new, "grp-1", "user-1", true);
    }

    @Test
    void groupPatchOpOff_skipsEnsureCreate_stillAddsMember() {
        var group = mock(GroupModel.class);
        var client = spy(newClient(false, group));
        doNothing().when(client).patchGroupMembership(GroupAdapter::new, "grp-1", "user-1", true);

        client.ensureGroupMembership(GroupAdapter::new, "grp-1", "user-1");

        verify(client, never()).create(GroupAdapter::new, group);
        verify(client).patchGroupMembership(GroupAdapter::new, "grp-1", "user-1", true);
    }

    @Test
    void missingLocalGroup_isSkipped() {
        var client = spy(newClient(true, null));

        client.ensureGroupMembership(GroupAdapter::new, "grp-1", "user-1");

        verify(client, never()).patchGroupMembership(GroupAdapter::new, "grp-1", "user-1", true);
    }
}
```

> Note on the lambda matcher: `GroupAdapter::new` is a fresh lambda instance per occurrence, so Mockito's default `equals` matching will not line up between stub and call. If `doNothing().when(client).create(GroupAdapter::new, group)` does not intercept, switch the stubs/verifications to `any()` for the factory argument: `doNothing().when(client).create(any(), eq(group))` and `verify(client).patchGroupMembership(any(), eq("grp-1"), eq("user-1"), eq(true))`. Use whichever the first red/green run proves correct.

- [ ] **Step 2: Run the test to verify it fails**

Run: `./gradlew test --tests "sh.libre.scim.core.EnsureGroupMembershipTest"`
Expected: FAIL — `ensureGroupMembership` does not exist (compile error), or method-not-found.

- [ ] **Step 3: Write the minimal implementation**

Add to `ScimClient.java` immediately after `patchGroupMembership` (after ~line 381). Reuses `create` (idempotent: short-circuits when a local mapping exists) and `patchGroupMembership` (single-member delta add, or `replace` fallback when `group-patchOp=false`).

```java
/**
 * Ensures a federated user's membership in one group is reflected in SCIM:
 * the SCIM group exists, and the user is a member. Used by the LDAP-import
 * path, which has no membership delta to work from and must re-assert current
 * memberships on every import (additions only — removals are out of scope).
 *
 * <p>Both underlying operations are idempotent, so re-asserting every import
 * is cheap after the first time: {@link #create} short-circuits once the
 * group has a local mapping, and the member-add is a single-member delta
 * PATCH the server already has.
 *
 * <p>When {@code group-patchOp=false}, {@link #patchGroupMembership} falls
 * back to a full {@code replace} that itself provisions the group and the
 * membership, so the explicit ensure-group {@link #create} is redundant and
 * skipped. A missing local group is logged and skipped.
 */
public void ensureGroupMembership(
        AdapterFactory<GroupModel, Group, GroupAdapter> factory,
        String groupId, String userId) {
    var group = session.groups().getGroupById(session.getContext().getRealm(), groupId);
    if (group == null) {
        LOGGER.infof("Skipping membership ensure: group %s not found locally", groupId);
        return;
    }
    if (this.model.get("group-patchOp", false)) {
        // Ensure the group exists remotely first; without a group mapping,
        // patchGroupMembership would skip on NoResultException. create()
        // short-circuits when the mapping already exists.
        this.create(factory, group);
    }
    this.patchGroupMembership(factory, groupId, userId, true);
}
```

- [ ] **Step 4: Run the test to verify it passes**

Run: `./gradlew test --tests "sh.libre.scim.core.EnsureGroupMembershipTest"`
Expected: PASS (3 tests). If a stub does not intercept, apply the `any()` matcher note from Step 1 and re-run.

- [ ] **Step 5: Run the full unit suite + commit**

Run: `./gradlew test`
Expected: BUILD SUCCESSFUL, no regressions.

```bash
git add src/main/java/sh/libre/scim/core/ScimClient.java \
        src/test/java/sh/libre/scim/core/EnsureGroupMembershipTest.java
git commit -m "feat(group): add ensureGroupMembership for federated-import propagation"
```

---

## Chunk 2: Wire into the LDAP import mapper

### Task 2: Dispatch group-membership propagation from `onImportUserFromLDAP`

**Files:**
- Modify: `src/main/java/sh/libre/scim/ldap/ScimLdapStorageMapper.java:34-58` (`onImportUserFromLDAP`)

This adds a second async dispatch, on `SCOPE_GROUP`, after the existing `SCOPE_USER` one. It captures only `userId` by value and re-fetches the user (and reads `getGroupsStream()`) in the worker's committed session — never the hook-thread `UserModel`. There is no isolated unit test for the mapper here (it has none today; the dispatch is exercised end-to-end in Chunk 3). Keep the diff minimal and mirror the existing async block exactly.

- [ ] **Step 1: Implement the dispatch**

In `onImportUserFromLDAP`, after the existing `if (isCreate) { … } else { … }` user-dispatch block (line 57), add:

```java
        // Propagate the user's current group memberships. LDAP-driven
        // membership changes never fire GROUP_MEMBERSHIP admin events, so this
        // hook is the only signal. Additions only; idempotent, so re-asserting
        // on every import is safe (see the design doc). Runs on SCOPE_GROUP,
        // independent of the user dispatch above — interoperates only with
        // components configured for both user and group propagation.
        dispatcher.runAsync(ScimDispatcher.SCOPE_GROUP, (client, workerSession) -> {
            var u = workerSession.users().getUserById(workerSession.getContext().getRealm(), userId);
            if (u == null) return;
            u.getGroupsStream().forEach(group ->
                client.ensureGroupMembership(sh.libre.scim.core.GroupAdapter::new, group.getId(), userId));
        });
```

> Add `import sh.libre.scim.core.GroupAdapter;` to the imports and use the short name instead of the fully-qualified reference if it reads cleaner alongside the existing `import sh.libre.scim.core.UserAdapter;`.

- [ ] **Step 2: Verify it compiles**

Run: `./gradlew compileJava`
Expected: BUILD SUCCESSFUL.

- [ ] **Step 3: Commit**

```bash
git add src/main/java/sh/libre/scim/ldap/ScimLdapStorageMapper.java
git commit -m "feat(ldap): propagate federated user's group memberships on import"
```

---

## Chunk 3: Integration coverage (LDAP group fixture)

### Task 3: Spike — provision an LDAP group fixture and confirm membership materializes

**Files:**
- Inspect: `src/integrationTest/java/sh/libre/scim/integration/IntegrationTestBase.java`
- Inspect: `src/integrationTest/resources/` (LDIF seed for the OpenLDAP container, if present)

This is the architectural unknown from the spec: is `user.getGroupsStream()` populated for a federated user, and does the test harness even model LDAP groups? Keycloak materializes LDAP groups via a separate **group-ldap-mapper** that must be attached to the LDAP provider. The existing harness (`newRealmWithScimAndLdap`) sets up users only.

- [ ] **Step 1: Determine the current LDAP fixture**

Run: `ls src/integrationTest/resources/ && grep -rn "ou=groups\|groupOfNames\|posixGroup\|memberUid\|member:" src/integrationTest/ | head`
Expected: identify whether the seed LDIF already contains a group with `alice` as a member. If not, it must be added.

- [ ] **Step 2: Add an LDAP group to the seed (if absent)**

Add an entry to the LDIF seed so `alice` is a member of a group, e.g.:

```ldif
dn: cn=engineers,ou=groups,dc=test,dc=local
objectClass: groupOfNames
cn: engineers
member: uid=alice,ou=users,dc=test,dc=local
member: uid=bob,ou=users,dc=test,dc=local
```

> Seed **both** `alice` and `bob` into the group so Task 4 Step 3's "second user into the same group" idempotency scenario works within a single realm/sync, without a second realm.

- [ ] **Step 3: Attach a group-ldap-mapper in the test realm setup**

Extend `IntegrationTestBase` with a helper that adds a `group-ldap-mapper` component to the LDAP provider (mode `READ_ONLY`, `groups.dn=ou=groups,dc=test,dc=local`, `membership.ldap.attribute=member`, `group.object.classes=groupOfNames`, `preserve.group.inheritance=false`, and importantly `groups.path=/`). Model it on how the existing `ldap-storage-mapper` user mappers are registered (search `IntegrationTestBase` for `ComponentRepresentation` / `userStorage().` mapper creation). Expose `newRealmWithScimAndLdapGroups(Consumer<MultivaluedHashMap<String,String>> cfg)` that builds on the **cfg-accepting** `newRealmWithScimAndLdapAndConfig` (so the config lambda Task 4 passes is honored — NOT the no-arg `newRealmWithScimAndLdap()`) and additionally attaches the group-ldap-mapper to the LDAP provider.

- [ ] **Step 4: Probe membership materialization**

Write a temporary scratch test (or reuse Task 4's first scenario) that lazy-imports `alice`, then asserts `session.users().getUserById(...).getGroupsStream()` is non-empty after a `triggerFullSync`. Confirm the group materializes. Record the trigger that works (lazy vs full sync) in a comment. Delete the scratch test once Task 4 covers it.

> If memberships do NOT materialize via the group-ldap-mapper in the container, STOP and surface to the human — the trigger-point assumption in the spec needs revisiting before continuing.

- [ ] **Step 5: Commit the fixture**

```bash
git add src/integrationTest/
git commit -m "test(ldap): add LDAP group fixture + group-ldap-mapper test helper"
```

### Task 4: End-to-end membership propagation scenarios

**Files:**
- Create: `src/integrationTest/java/sh/libre/scim/integration/ScimLdapGroupMembershipIT.java`

Mirror the WireMock/await idiom in `ScimGroupPropagationIT` (group POST stub, group membership PATCH stub, `await().atMost(20, SECONDS)` count assertions). Configure the SCIM provider with **both** `propagation-user=true` and `propagation-group=true` and `group-patchOp=true` (the same-component requirement from the spec). Reuse existing WireMock stub helpers (`stubScimGroupCreateOk`, the membership PATCH stub) where present; add a group-members PATCH stub if missing.

- [ ] **Step 1: Write the first failing scenario — group ensured + member added**

```java
@Test
void federatedUserGroupMembershipIsProvisionedAndAdded() {
    stubScimUserCreateOk();
    stubScimGroupCreateOk();
    stubScimGroupPatchOk(); // existing helper: patch(urlMatching("/Groups/.*"))
    var r = newRealmWithScimAndLdapGroups(cfg -> {
        cfg.putSingle("propagation-user", "true");
        cfg.putSingle("propagation-group", "true");
        cfg.putSingle("group-patchOp", "true");
    });

    // Import alice (member of cn=engineers in LDAP).
    r.realm().userStorage().syncUsers(r.ldapId(), "triggerFullSync");

    await().atMost(20, SECONDS).untilAsserted(() -> {
        // Group provisioned (first-time create) ...
        int groupPosts = wireMock.countRequestsMatching(
            postRequestedFor(urlPathMatching("/Groups")).build()).getCount();
        // ... and a single-member add PATCH targets it.
        int memberPatches = wireMock.countRequestsMatching(
            patchRequestedFor(urlPathMatching("/Groups/.*"))
                .withRequestBody(matchingJsonPath("$.Operations[?(@.op == 'add')]"))
                .build()).getCount();
        assertTrue(groupPosts >= 1, "expected the federated group to be provisioned, got " + groupPosts);
        assertTrue(memberPatches >= 1, "expected a member-add PATCH, got " + memberPatches);
    });
}
```

- [ ] **Step 2: Run it (red, then green)**

Run: `./gradlew integrationTest --tests "sh.libre.scim.integration.ScimLdapGroupMembershipIT"`
Expected: with Chunks 1–2 implemented, this should pass. If the member-add does not fire, check the same-component config and the Task 3 trigger finding. (This IT requires Docker.)

- [ ] **Step 3: Add the idempotency / no-duplicate scenario**

Add a second test: trigger `triggerFullSync` twice (or import a second LDAP user into the same group) and assert the group is provisioned only once (POST count stays 1 after the mapping exists) while member-adds still fire per user. Asserts decision 3 (every-import re-assertion) + the one-time-per-group create.

- [ ] **Step 4: Run the IT, then commit**

Run: `./gradlew integrationTest --tests "sh.libre.scim.integration.ScimLdapGroupMembershipIT"`
Expected: PASS (2 scenarios).

```bash
git add src/integrationTest/java/sh/libre/scim/integration/ScimLdapGroupMembershipIT.java
git commit -m "test(ldap): integration-cover federated group membership propagation"
```

---

## Chunk 4: Documentation

### Task 5: Mark the roadmap item and document the operator requirement

**Files:**
- Modify: `docs/roadmap.md` (the "Group propagation overhaul" → "LDAP-federated group membership" bullet, ~line 30)
- Modify: `docs/ldap-federation-support.md` (note the new behavior + the both-scopes/same-component operator requirement)

- [ ] **Step 1: Update `docs/roadmap.md`**

Replace the open "LDAP-federated group membership" bullet with a `_Done._` entry: federated users' current memberships now propagate via `ScimLdapStorageMapper` → `ScimClient.ensureGroupMembership` (ensure group exists, then single-member delta add), additions-only, re-asserted every import. Note that **membership removal** remains a deferred reconciler-style follow-up, and call out the same-component (`propagation-user` + `propagation-group`) requirement. Verified by `EnsureGroupMembershipTest` and `ScimLdapGroupMembershipIT`.

- [ ] **Step 2: Update `docs/ldap-federation-support.md`**

Under the group-membership discussion, document: (a) the new propagation path; (b) the operator requirement that the SCIM provider component enable both `propagation-user` and `propagation-group` for membership to resolve the member's external ID; (c) the accepted lazy-import lag (converges on next periodic sync); (d) removal-of-membership is not yet handled.

- [ ] **Step 3: Commit**

```bash
git add docs/roadmap.md docs/ldap-federation-support.md
git commit -m "docs(ldap): mark federated group membership done; note operator requirements"
```

---

## Done criteria

- [ ] `./gradlew test` passes (incl. `EnsureGroupMembershipTest`).
- [ ] `./gradlew integrationTest --tests "sh.libre.scim.integration.ScimLdapGroupMembershipIT"` passes (Docker required).
- [ ] Roadmap item flipped to _Done_; operator requirements documented.
- [ ] No CHANGELOG.md edit (release-please regenerates it from conventional commits).

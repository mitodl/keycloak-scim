package sh.libre.scim.integration;

import org.junit.jupiter.api.Test;

import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;

/**
 * End-to-end propagation of LDAP-federated group memberships. When an LDAP
 * user is imported (here via a full federation sync), our
 * {@code ScimLdapStorageMapper.onImportUserFromLDAP} iterates the imported
 * user's Keycloak groups (materialized by the attached group-ldap-mapper)
 * and, with {@code propagation-group=true}, calls
 * {@code ScimClient.ensureGroupMembership} for each. That ensures the SCIM
 * group exists (POST /Groups on first sight) and adds the member via a
 * single-member delta PATCH (op=add) when {@code group-patchOp=true}.
 *
 * <p>The LDIF seed places {@code alice} and {@code bob} in {@code cn=engineers}
 * (a {@code groupOfNames}), so one full sync imports both into the same group.
 *
 * <p>Unlike the admin-REST event-listener path exercised in
 * {@code ScimGroupPropagationIT}, the federated-import path is driven by the
 * mapper, so the {@code scim} event listener is not required here.
 */
class ScimLdapGroupMembershipIT extends IntegrationTestBase {

    @Test
    void federatedUserGroupMembershipIsProvisionedAndAdded() {
        stubScimUserCreateOk();
        stubScimGroupCreateOk();
        stubScimGroupPatchOk();

        var r = newRealmWithScimAndLdapGroups(cfg -> {
            cfg.putSingle("propagation-user", "true");
            cfg.putSingle("propagation-group", "true");
            cfg.putSingle("group-patchOp", "true");
        });

        // Full federation sync imports alice and bob, each a member of the
        // seeded 'engineers' group. onImportUserFromLDAP propagates the
        // membership for each imported user.
        r.realm().userStorage().syncUsers(r.ldapId(), "triggerFullSync");

        // First, wait for the engineers group to be provisioned (POST /Groups).
        // Separating this await from the member-add await gives the same
        // journal-ordering protection as groupProvisionedOnceAcrossMultipleMembers.
        await().atMost(20, SECONDS).until(() ->
            wireMock.countRequestsMatching(
                postRequestedFor(urlPathEqualTo("/Groups")).build()
            ).getCount() >= 1);

        // Then wait for both seeded members (alice, bob) to be added via a
        // single-member delta add PATCH. The LDIF seeds exactly two members and
        // a full sync imports both, so the floor is >= 2.
        awaitMemberAddPatchCount(2);
    }

    @Test
    void groupProvisionedOnceAcrossMultipleMembers() {
        stubScimUserCreateOk();
        stubScimGroupCreateOk();
        stubScimGroupPatchOk();

        var r = newRealmWithScimAndLdapGroups(cfg -> {
            cfg.putSingle("propagation-user", "true");
            cfg.putSingle("propagation-group", "true");
            cfg.putSingle("group-patchOp", "true");
        });

        r.realm().userStorage().syncUsers(r.ldapId(), "triggerFullSync");

        // First, wait for the group to be provisioned (POST /Groups). The
        // provisioning POST is what creates the local group mapping that
        // subsequent member PATCHes target.
        await().atMost(20, SECONDS).until(() ->
            wireMock.countRequestsMatching(
                postRequestedFor(urlPathEqualTo("/Groups")).build()
            ).getCount() >= 1);

        // Then wait until both members (alice, bob) have been asserted via a
        // member-add PATCH. Both belong to 'engineers', so a single full sync
        // produces a member-add per user.
        awaitMemberAddPatchCount(2);

        // The group is provisioned exactly once: the first ensureGroupMembership
        // creates it (POST /Groups), and every subsequent ensure short-circuits
        // on the now-existing local group mapping rather than POSTing again. We
        // assert this only after both member PATCHes are observed, so we are not
        // racing a still-in-flight second provisioning.
        int groupPosts = wireMock.countRequestsMatching(
            postRequestedFor(urlPathEqualTo("/Groups")).build()
        ).getCount();
        assertEquals(1, groupPosts,
            "engineers group must be provisioned exactly once across both members' "
                + "membership propagation, got " + groupPosts);
    }
}

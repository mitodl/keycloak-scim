package sh.libre.scim.core;

import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.spy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.Test;
import org.keycloak.common.util.MultivaluedHashMap;
import org.keycloak.component.ComponentModel;
import org.keycloak.models.GroupModel;
import org.keycloak.models.GroupProvider;
import org.keycloak.models.KeycloakContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.mockito.InOrder;

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

        // ScimClient ctor does not touch the session; the spy stubs the public methods under test
        return new ScimClient(model, session);
    }

    @Test
    void groupPatchOpOn_ensuresGroupThenAddsMember() {
        var group = mock(GroupModel.class);
        var client = spy(newClient(true, group));
        doNothing().when(client).create(any(), eq(group));
        doNothing().when(client).patchGroupMembership(any(), eq("grp-1"), eq("user-1"), eq(true));

        client.ensureGroupMembership(GroupAdapter::new, "grp-1", "user-1");

        InOrder order = inOrder(client);
        order.verify(client).create(any(), eq(group));
        order.verify(client).patchGroupMembership(any(), eq("grp-1"), eq("user-1"), eq(true));
    }

    @Test
    void groupPatchOpOff_skipsEnsureCreate_stillAddsMember() {
        var group = mock(GroupModel.class);
        var client = spy(newClient(false, group));
        doNothing().when(client).patchGroupMembership(any(), eq("grp-1"), eq("user-1"), eq(true));

        client.ensureGroupMembership(GroupAdapter::new, "grp-1", "user-1");

        verify(client, never()).create(any(), eq(group));
        verify(client).patchGroupMembership(any(), eq("grp-1"), eq("user-1"), eq(true));
    }

    @Test
    void missingLocalGroup_isSkipped() {
        var client = spy(newClient(true, null));

        client.ensureGroupMembership(GroupAdapter::new, "grp-1", "user-1");

        verify(client, never()).create(any(), any());
        verify(client, never()).patchGroupMembership(any(), eq("grp-1"), eq("user-1"), eq(true));
    }
}

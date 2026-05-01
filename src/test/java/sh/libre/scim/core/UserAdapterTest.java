package sh.libre.scim.core;

import jakarta.persistence.EntityManager;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.keycloak.component.ComponentModel;
import org.keycloak.connections.jpa.JpaConnectionProvider;
import org.keycloak.models.GroupModel;
import org.keycloak.models.GroupProvider;
import org.keycloak.models.KeycloakContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import org.mockito.junit.jupiter.MockitoSettings;
import org.mockito.quality.Strictness;

import java.util.stream.Collectors;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
@MockitoSettings(strictness = Strictness.LENIENT)
class UserAdapterTest {

    private static final String COMPONENT_ID = "component-id";

    @Mock KeycloakSession session;
    @Mock KeycloakContext context;
    @Mock RealmModel realm;
    @Mock ComponentModel component;
    @Mock JpaConnectionProvider jpaConnectionProvider;
    @Mock EntityManager entityManager;
    @Mock GroupProvider groupProvider;

    private UserAdapter adapter;

    @BeforeEach
    void setUp() {
        when(session.getContext()).thenReturn(context);
        when(context.getRealm()).thenReturn(realm);
        when(realm.getId()).thenReturn("realm-id");
        when(session.getProvider(JpaConnectionProvider.class)).thenReturn(jpaConnectionProvider);
        when(jpaConnectionProvider.getEntityManager()).thenReturn(entityManager);

        adapter = new UserAdapter(session, COMPONENT_ID);
        adapter.setActive(true);
        adapter.setRoles(new String[]{});
    }

    @Test
    void toScimUsesUsernameByDefault() {
        when(realm.getComponent(COMPONENT_ID)).thenReturn(component);
        when(component.get("username-source")).thenReturn(null);

        adapter.setUsername("alice");
        adapter.setEmail("alice@example.com");

        var user = adapter.toSCIM(false);
        assertEquals("alice", user.getUserName().orElse(null));
    }

    @Test
    void toScimUsesEmailWhenConfigured() {
        when(realm.getComponent(COMPONENT_ID)).thenReturn(component);
        when(component.get("username-source")).thenReturn("email");

        adapter.setUsername("alice");
        adapter.setEmail("alice@example.com");

        var user = adapter.toSCIM(false);
        assertEquals("alice@example.com", user.getUserName().orElse(null));
    }

    @Test
    void toScimFallsBackToUsernameWhenEmailSourceConfiguredButEmailMissing() {
        when(realm.getComponent(COMPONENT_ID)).thenReturn(component);
        when(component.get("username-source")).thenReturn("email");

        adapter.setUsername("alice");
        // no email set

        var user = adapter.toSCIM(false);
        assertEquals("alice", user.getUserName().orElse(null));
    }

    @Test
    void toScimFallsBackToUsernameWhenComponentMissing() {
        when(realm.getComponent(COMPONENT_ID)).thenReturn(null);

        adapter.setUsername("alice");
        adapter.setEmail("alice@example.com");

        var user = adapter.toSCIM(false);
        assertEquals("alice", user.getUserName().orElse(null));
    }

    @Test
    void toScimEmailHasWorkTypeAndPrimaryFlag() {
        when(realm.getComponent(COMPONENT_ID)).thenReturn(component);

        adapter.setUsername("alice");
        adapter.setEmail("alice@example.com");

        var user = adapter.toSCIM(false);
        var emails = user.getEmails();

        assertEquals(1, emails.size());
        var email = emails.get(0);
        assertEquals("alice@example.com", email.getValue().orElse(null));
        assertEquals("work", email.getType().orElse(null));
        assertTrue(email.isPrimary());
    }

    @Test
    void toScimEmitsNoEmailEntryWhenEmailMissing() {
        when(realm.getComponent(COMPONENT_ID)).thenReturn(component);

        adapter.setUsername("alice");
        // no email

        var user = adapter.toSCIM(false);
        assertTrue(user.getEmails().isEmpty());
    }

    @Test
    void getFilteredGroupsReturnsEmptyWhenNoFilterConfigured() {
        when(realm.getComponent(COMPONENT_ID)).thenReturn(component);
        when(component.get("group-filter")).thenReturn(null);

        var result = adapter.getFilteredGroups().collect(Collectors.toList());
        assertTrue(result.isEmpty());
    }

    @Test
    void getFilteredGroupsReturnsEmptyWhenFilterBlank() {
        when(realm.getComponent(COMPONENT_ID)).thenReturn(component);
        when(component.get("group-filter")).thenReturn("   ");

        var result = adapter.getFilteredGroups().collect(Collectors.toList());
        assertTrue(result.isEmpty());
    }

    @Test
    void getFilteredGroupsMatchesByRegex() {
        when(realm.getComponent(COMPONENT_ID)).thenReturn(component);
        when(component.get("group-filter")).thenReturn("admins,team-.*");
        when(session.groups()).thenReturn(groupProvider);

        var admins = mock(GroupModel.class);
        when(admins.getName()).thenReturn("admins");
        when(admins.getSubGroupsStream()).thenReturn(Stream.empty());

        var teamAlpha = mock(GroupModel.class);
        when(teamAlpha.getName()).thenReturn("team-alpha");
        when(teamAlpha.getSubGroupsStream()).thenReturn(Stream.empty());

        var other = mock(GroupModel.class);
        when(other.getName()).thenReturn("other");

        when(groupProvider.getGroupsStream(realm))
            .thenReturn(Stream.of(admins, teamAlpha, other));

        var result = adapter.getFilteredGroups().collect(Collectors.toSet());
        assertEquals(2, result.size());
        assertTrue(result.contains(admins));
        assertTrue(result.contains(teamAlpha));
        assertFalse(result.contains(other));
    }

    @Test
    void getFilteredGroupsIncludesSubgroupsRecursively() {
        when(realm.getComponent(COMPONENT_ID)).thenReturn(component);
        when(component.get("group-filter")).thenReturn("parent");
        when(session.groups()).thenReturn(groupProvider);

        var leaf = mock(GroupModel.class);
        when(leaf.getSubGroupsStream()).thenReturn(Stream.empty());

        var child = mock(GroupModel.class);
        when(child.getSubGroupsStream()).thenReturn(Stream.of(leaf));

        var parent = mock(GroupModel.class);
        when(parent.getName()).thenReturn("parent");
        when(parent.getSubGroupsStream()).thenReturn(Stream.of(child));

        when(groupProvider.getGroupsStream(realm)).thenReturn(Stream.of(parent));

        var result = adapter.getFilteredGroups().collect(Collectors.toSet());
        assertEquals(3, result.size());
        assertTrue(result.contains(parent));
        assertTrue(result.contains(child));
        assertTrue(result.contains(leaf));
    }
}

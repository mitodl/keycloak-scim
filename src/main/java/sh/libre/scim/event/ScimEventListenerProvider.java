package sh.libre.scim.event;

import java.util.regex.*;

import org.jboss.logging.Logger;
import org.keycloak.events.Event;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.EventType;
import org.keycloak.events.admin.AdminEvent;
import org.keycloak.events.admin.OperationType;
import org.keycloak.events.admin.ResourceType;
import org.keycloak.models.GroupModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.UserModel;

import sh.libre.scim.core.GroupAdapter;
import sh.libre.scim.core.ScimDispatcher;
import sh.libre.scim.core.UserAdapter;

public class ScimEventListenerProvider implements EventListenerProvider {
    final Logger LOGGER = Logger.getLogger(ScimEventListenerProvider.class);
    ScimDispatcher dispatcher;
    KeycloakSession session;

    public ScimEventListenerProvider(KeycloakSession session) {
        this.session = session;
        dispatcher = new ScimDispatcher(session);
    }

    @Override
    public void close() {
    }

    @Override
    public void onEvent(Event event) {
        if (event.getType() == EventType.REGISTER) {
            var user = getUser(event.getUserId());
            dispatcher.run(ScimDispatcher.SCOPE_USER, (client) -> client.create(UserAdapter.class, user));
        }
        if (event.getType() == EventType.UPDATE_EMAIL || event.getType() == EventType.UPDATE_PROFILE) {
            var user = getUser(event.getUserId());
            dispatcher.run(ScimDispatcher.SCOPE_USER, (client) -> client.replace(UserAdapter.class, user));
        }
        if (event.getType() == EventType.DELETE_ACCOUNT) {
            dispatcher.run(ScimDispatcher.SCOPE_USER, (client) -> client.delete(UserAdapter.class, event.getUserId()));
        }
    }

    @Override
    public void onEvent(AdminEvent event, boolean includeRepresentation) {
        if (event.getResourceType() == ResourceType.USER) {
            var userId = event.getResourcePath().replace("users/", "");
            LOGGER.infof("%s %s", userId, event.getOperationType());
            if (event.getOperationType() == OperationType.CREATE) {
                var user = getUser(userId);
                dispatcher.run(ScimDispatcher.SCOPE_USER, (client) -> client.create(UserAdapter.class, user));
                user.getGroupsStream().forEach(group -> {
                    dispatcher.run(ScimDispatcher.SCOPE_GROUP, (client) -> client.replace(GroupAdapter.class, group));
                });
            }
            if (event.getOperationType() == OperationType.UPDATE) {
                var user = getUser(userId);
                dispatcher.run(ScimDispatcher.SCOPE_USER, (client) -> client.replace(UserAdapter.class, user));
            }
            if (event.getOperationType() == OperationType.DELETE) {
                dispatcher.run(ScimDispatcher.SCOPE_USER, (client) -> client.delete(UserAdapter.class, userId));
            }
        }
        if (event.getResourceType() == ResourceType.GROUP) {
            var groupId = event.getResourcePath().replace("groups/", "");
            LOGGER.infof("%s %s", event.getResourcePath(), event.getOperationType());
            if (event.getOperationType() == OperationType.CREATE) {
                var group = getGroup(groupId);
                dispatcher.run(ScimDispatcher.SCOPE_GROUP, (client) -> client.create(GroupAdapter.class, group));
            }
            if (event.getOperationType() == OperationType.UPDATE) {
                var group = getGroup(groupId);
                dispatcher.run(ScimDispatcher.SCOPE_GROUP, (client) -> client.replace(GroupAdapter.class, group));
            }
            if (event.getOperationType() == OperationType.DELETE) {
                dispatcher.run(ScimDispatcher.SCOPE_GROUP, (client) -> client.delete(GroupAdapter.class, groupId));
            }
        }
        if (event.getResourceType() == ResourceType.GROUP_MEMBERSHIP) {
            Pattern pattern = Pattern.compile("users/(.+)/groups/(.+)");
            Matcher matcher = pattern.matcher(event.getResourcePath());
            if (matcher.find()) {
                var userId = matcher.group(1);
                var groupId = matcher.group(2);
                LOGGER.infof("%s %s from %s", event.getOperationType(), userId, groupId);
                var group = getGroup(groupId);
                dispatcher.run(ScimDispatcher.SCOPE_GROUP, (client) -> client.replace(GroupAdapter.class, group));
                var user = getUser(userId);
                dispatcher.run(ScimDispatcher.SCOPE_USER, (client) -> client.replace(UserAdapter.class, user));
            }
        }
        if (event.getResourceType() == ResourceType.REALM_ROLE_MAPPING) {
            Pattern pattern = Pattern.compile("^(.+)/(.+)/role-mappings");
            Matcher matcher = pattern.matcher(event.getResourcePath());
            if (matcher.find()) {
                var type = matcher.group(1);
                var id = matcher.group(2);
                LOGGER.infof("%s %s %s roles", event.getOperationType(), type, id);
                if (type.equals("users")) {
                    var user = getUser(id);
                    dispatcher.run(ScimDispatcher.SCOPE_USER, (client) -> client.replace(UserAdapter.class, user));
                } else if (type.equals("groups")) {
                    var group = getGroup(id);
                    session.users().getGroupMembersStream(session.getContext().getRealm(), group).forEach(user -> {
                        dispatcher.run(ScimDispatcher.SCOPE_USER, (client) -> client.replace(UserAdapter.class, user));
                    });
                }
            }
        }
    }

    private UserModel getUser(String id) {
        return session.users().getUserById(session.getContext().getRealm(), id);
    }

    private GroupModel getGroup(String id) {
        return session.groups().getGroupById(session.getContext().getRealm(), id);
    }
}

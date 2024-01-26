package sh.libre.scim.event;

import java.util.HashMap;
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
    HashMap<ResourceType, Pattern> patterns = new HashMap<ResourceType, Pattern>();

    public ScimEventListenerProvider(KeycloakSession session) {
        this.session = session;
        dispatcher = new ScimDispatcher(session);
        patterns.put(ResourceType.USER, Pattern.compile("users/(.+)"));
        patterns.put(ResourceType.GROUP, Pattern.compile("groups/([\\w-]+)(/children)?"));
        patterns.put(ResourceType.GROUP_MEMBERSHIP, Pattern.compile("users/(.+)/groups/(.+)"));
        patterns.put(ResourceType.REALM_ROLE_MAPPING, Pattern.compile("^(.+)/(.+)/role-mappings"));
    }

    @Override
    public void close() {
    }

    @Override
    public void onEvent(Event event) {
        if (event.getType() == EventType.REGISTER) {
            var user = getUser(event.getUserId());
            dispatcher.run(ScimDispatcher.SCOPE_USER, client -> client.create(UserAdapter.class, user));
        }
        if (event.getType() == EventType.UPDATE_EMAIL || event.getType() == EventType.UPDATE_PROFILE) {
            var user = getUser(event.getUserId());
            dispatcher.run(ScimDispatcher.SCOPE_USER, client -> client.replace(UserAdapter.class, user));
        }
        if (event.getType() == EventType.DELETE_ACCOUNT) {
            dispatcher.run(ScimDispatcher.SCOPE_USER, client -> client.delete(UserAdapter.class, event.getUserId()));
        }
    }

    @Override
    public void onEvent(AdminEvent event, boolean includeRepresentation) {
        var pattern = patterns.get(event.getResourceType());
        if (pattern == null) {
            return;
        }
        var matcher = pattern.matcher(event.getResourcePath());
        if (!matcher.find()) {
            return;
        }
        if (event.getResourceType() == ResourceType.USER) {
            var userId = matcher.group(1);
            LOGGER.infof("%s %s", userId, event.getOperationType());
            if (event.getOperationType() == OperationType.CREATE) {
                var user = getUser(userId);
                dispatcher.run(ScimDispatcher.SCOPE_USER, client -> client.create(UserAdapter.class, user));
                user.getGroupsStream().forEach(group -> {
                    dispatcher.run(ScimDispatcher.SCOPE_GROUP, client -> client.replace(GroupAdapter.class, group));
                });
            }
            if (event.getOperationType() == OperationType.UPDATE) {
                var user = getUser(userId);
                dispatcher.run(ScimDispatcher.SCOPE_USER, client -> client.replace(UserAdapter.class, user));
            }
            if (event.getOperationType() == OperationType.DELETE) {
                dispatcher.run(ScimDispatcher.SCOPE_USER, client -> client.delete(UserAdapter.class, userId));
            }
        }
        if (event.getResourceType() == ResourceType.GROUP) {
            var groupId = matcher.group(1);
            LOGGER.infof("group %s %s", groupId, event.getOperationType());
            if (event.getOperationType() == OperationType.CREATE) {
                var group = getGroup(groupId);
                dispatcher.run(ScimDispatcher.SCOPE_GROUP, client -> client.create(GroupAdapter.class, group));
            }
            if (event.getOperationType() == OperationType.UPDATE) {
                var group = getGroup(groupId);
                dispatcher.run(ScimDispatcher.SCOPE_GROUP, client -> client.replace(GroupAdapter.class, group));
            }
            if (event.getOperationType() == OperationType.DELETE) {
                dispatcher.run(ScimDispatcher.SCOPE_GROUP,
                        client -> client.delete(GroupAdapter.class, groupId));
            }
        }
        if (event.getResourceType() == ResourceType.GROUP_MEMBERSHIP) {
            var userId = matcher.group(1);
            var groupId = matcher.group(2);
            LOGGER.infof("%s %s from %s", event.getOperationType(), userId, groupId);
            var group = getGroup(groupId);
            dispatcher.run(ScimDispatcher.SCOPE_GROUP, client -> client.replace(GroupAdapter.class, group));
            var user = getUser(userId);
            dispatcher.run(ScimDispatcher.SCOPE_USER, client -> client.replace(UserAdapter.class, user));
        }
        if (event.getResourceType() == ResourceType.REALM_ROLE_MAPPING) {
            var type = matcher.group(1);
            var id = matcher.group(2);
            LOGGER.infof("%s %s %s roles", event.getOperationType(), type, id);
            if ("users".equals(type)) {
                var user = getUser(id);
                dispatcher.run(ScimDispatcher.SCOPE_USER, client -> client.replace(UserAdapter.class, user));
            } else if ("groups".equals(type)) {
                var group = getGroup(id);
                session.users().getGroupMembersStream(session.getContext().getRealm(), group).forEach(user -> {
                    dispatcher.run(ScimDispatcher.SCOPE_USER, client -> client.replace(UserAdapter.class, user));
                });
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

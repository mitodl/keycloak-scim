package sh.libre.scim.event;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;

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
import org.keycloak.representations.idm.GroupRepresentation;

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
            dispatcher.run((client) -> client.create(UserAdapter.class, user));
        }
        if (event.getType() == EventType.UPDATE_EMAIL || event.getType() == EventType.UPDATE_PROFILE) {
            var user = getUser(event.getUserId());
            dispatcher.run((client) -> client.replace(UserAdapter.class, user));
        }
        if (event.getType() == EventType.DELETE_ACCOUNT) {
            dispatcher.run((client) -> client.delete(UserAdapter.class, event.getUserId()));
        }
    }

    @Override
    public void onEvent(AdminEvent event, boolean includeRepresentation) {
        if (event.getResourceType() == ResourceType.USER) {
            var userId = event.getResourcePath().replace("users/", "");
            LOGGER.infof("%s %s", userId, event.getOperationType());
            if (event.getOperationType() == OperationType.CREATE) {
                var user = getUser(userId);
                dispatcher.run((client) -> client.create(UserAdapter.class, user));
            }
            if (event.getOperationType() == OperationType.UPDATE) {
                var user = getUser(userId);
                dispatcher.run((client) -> client.replace(UserAdapter.class, user));
            }
            if (event.getOperationType() == OperationType.DELETE) {
                dispatcher.run((client) -> client.delete(UserAdapter.class, userId));
            }
        }
        if (event.getResourceType() == ResourceType.GROUP) {
            var groupId = event.getResourcePath().replace("groups/", "");
            LOGGER.infof("%s %s", event.getResourcePath(), event.getOperationType());
            if (event.getOperationType() == OperationType.CREATE) {
                var group = getGroup(groupId);
                dispatcher.run((client) -> client.create(GroupAdapter.class, group));
            }
            if (event.getOperationType() == OperationType.UPDATE) {
                var group = getGroup(groupId);
                dispatcher.run((client) -> client.replace(GroupAdapter.class, group));
            }
            if (event.getOperationType() == OperationType.DELETE) {
                dispatcher.run((client) -> client.delete(GroupAdapter.class, groupId));
            }
        }
        if (event.getResourceType() == ResourceType.GROUP_MEMBERSHIP) {
            ObjectMapper obj = new ObjectMapper();
            try {
                var groupRepresentation = obj.readValue(event.getRepresentation(), GroupRepresentation.class);
                var group = getGroup(groupRepresentation.getId());
                dispatcher.run((client) -> client.replace(GroupAdapter.class, group));
            } catch (JsonProcessingException e) {
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

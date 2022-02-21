package sh.libre.scim.event;

import org.jboss.logging.Logger;
import org.keycloak.events.Event;
import org.keycloak.events.EventListenerProvider;
import org.keycloak.events.EventType;
import org.keycloak.events.admin.AdminEvent;
import org.keycloak.events.admin.OperationType;
import org.keycloak.events.admin.ResourceType;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.UserModel;

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
                // session.getTransactionManager().rollback();
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
        if (event.getResourceType() == ResourceType.COMPONENT) {
            if (event.getOperationType() == OperationType.CREATE
                    || (event.getOperationType() == OperationType.UPDATE)) {
                LOGGER.infof("%s %s", event.getResourcePath(), event.getOperationType());
                // dispatcher.run((client) -> client.syncUsers());
            }
        }
    }

    private UserModel getUser(String id) {
        return session.users().getUserById(session.getContext().getRealm(), id);
    }
}

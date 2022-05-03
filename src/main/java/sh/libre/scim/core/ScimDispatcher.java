package sh.libre.scim.core;

import java.util.function.Consumer;
import org.jboss.logging.Logger;
import org.keycloak.models.KeycloakSession;

import sh.libre.scim.storage.ScimStorageProviderFactory;

public class ScimDispatcher {
    public static final String SCOPE_USER = "user"; 
    public static final String SCOPE_GROUP = "group"; 

    final private KeycloakSession session;
    final private Logger LOGGER = Logger.getLogger(ScimDispatcher.class);

    public ScimDispatcher(KeycloakSession session) {
        this.session = session;
    }

    public void run(String scope, Consumer<ScimClient> f) {
        session.getContext().getRealm().getComponentsStream()
                .filter((m) -> {
                    return ScimStorageProviderFactory.ID.equals(m.getProviderId()) && m.get("enabled").equals("true") &&  m.get("propagation-"+scope).equals("true");
                })
                .forEach(m -> {
                    LOGGER.infof("%s %s %s %s", m.getId(), m.getName(), m.getProviderId(), m.getProviderType());
                    var client = new ScimClient(m, session);
                    try {
                        f.accept(client);
                    } finally {
                        client.close();
                    }
                });
    }
}

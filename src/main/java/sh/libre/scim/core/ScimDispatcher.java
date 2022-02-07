package sh.libre.scim.core;

import java.util.ArrayList;
import java.util.function.Consumer;
import org.jboss.logging.Logger;
import javax.persistence.EntityManager;

import org.keycloak.connections.jpa.JpaConnectionProvider;
import org.keycloak.models.KeycloakSession;

public class ScimDispatcher {
    final KeycloakSession session;
    final EntityManager entityManager;
    final Logger LOGGER = Logger.getLogger(ScimDispatcher.class);
    ArrayList<ScimClient> clients = new ArrayList<ScimClient>();

    public ScimDispatcher(KeycloakSession session) {
        this.session = session;
        entityManager = session.getProvider(JpaConnectionProvider.class).getEntityManager();
        reloadClients();
    }

    public void reloadClients() {
        close();
        LOGGER.info("Cleared SCIM Clients");
        var realm = session.getContext().getRealm();
        clients = new ArrayList<ScimClient>();
        var kcClients = session.clients().getClientsStream(realm);
        for (var kcClient : kcClients.toList()) {
            var endpoint = kcClient.getAttribute("scim-endpoint");
            var name = kcClient.getAttribute("scim-name");
            if (endpoint != "") {
                if (name == "") {
                    name = kcClient.getName();
                }
                clients.add(new ScimClient(
                        name,
                        endpoint,
                        realm.getId(),
                        entityManager));
                LOGGER.infof("Added %s SCIM Client (%s)", name, endpoint);
            }
        }

    }

    public void close() {
        for (var client : clients) {
            client.close();
        }
    }

    public void run(Consumer<ScimClient> f) {
        for (var client : clients) {
            f.accept(client);
        }
    }
}

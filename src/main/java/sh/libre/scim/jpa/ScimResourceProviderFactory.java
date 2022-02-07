package sh.libre.scim.jpa;

import org.keycloak.Config.Scope;
import org.keycloak.connections.jpa.entityprovider.JpaEntityProvider;
import org.keycloak.connections.jpa.entityprovider.JpaEntityProviderFactory;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;

public class ScimResourceProviderFactory implements JpaEntityProviderFactory {
    final static String ID ="scim-resource";
    @Override
    public void close() {
    }

    @Override
    public JpaEntityProvider create(KeycloakSession session) {
        return new ScimResourceProvider();
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public void init(Scope scope) {
    }

    @Override
    public void postInit(KeycloakSessionFactory sessionFactory) {
    }
}

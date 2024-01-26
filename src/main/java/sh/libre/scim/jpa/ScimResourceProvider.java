package sh.libre.scim.jpa;

import java.util.List;

import org.keycloak.connections.jpa.entityprovider.JpaEntityProvider;

import java.util.Collections;

public class ScimResourceProvider implements JpaEntityProvider {

    @Override
    public List<Class<?>> getEntities() {
        return Collections.singletonList(ScimResource.class);
    }

    @Override
    public String getChangelogLocation() {
        return "META-INF/scim-resource-changelog.xml";
    }

    @Override
    public void close() {
    }

    @Override
    public String getFactoryId() {
        return ScimResourceProviderFactory.ID;
    }
}

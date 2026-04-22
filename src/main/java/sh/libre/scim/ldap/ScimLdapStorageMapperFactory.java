package sh.libre.scim.ldap;

import org.keycloak.component.ComponentModel;
import org.keycloak.component.ComponentValidationException;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.KeycloakSessionFactory;
import org.keycloak.models.RealmModel;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.storage.ldap.mappers.LDAPStorageMapper;
import org.keycloak.storage.ldap.mappers.LDAPStorageMapperFactory;

import java.util.Collections;
import java.util.List;

import sh.libre.scim.core.ScimDispatcher;

public class ScimLdapStorageMapperFactory implements LDAPStorageMapperFactory<LDAPStorageMapper> {

    public static final String ID = "scim-ldap-sync";
    private static final String HELP_TEXT =
        "Propagates LDAP-imported and updated users to configured SCIM service providers.";

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public String getHelpText() {
        return HELP_TEXT;
    }

    @Override
    public LDAPStorageMapper create(KeycloakSession session, ComponentModel model) {
        return new ScimLdapStorageMapper(new ScimDispatcher(session));
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return Collections.emptyList();
    }

    @Override
    public void validateConfiguration(KeycloakSession session, RealmModel realm, ComponentModel model)
            throws ComponentValidationException {
        // no config to validate
    }

    @Override
    public void init(org.keycloak.Config.Scope config) {
        // no-op
    }

    @Override
    public void postInit(KeycloakSessionFactory factory) {
        // no-op
    }

    @Override
    public void close() {
        // no-op
    }
}

package sh.libre.scim.ldap;

import org.jboss.logging.Logger;
import org.keycloak.models.GroupModel;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleModel;
import org.keycloak.models.UserModel;
import org.keycloak.storage.ldap.LDAPStorageProvider;
import org.keycloak.storage.ldap.idm.model.LDAPObject;
import org.keycloak.storage.ldap.idm.query.internal.LDAPQuery;
import org.keycloak.storage.ldap.mappers.LDAPStorageMapper;
import org.keycloak.storage.user.SynchronizationResult;

import javax.naming.AuthenticationException;
import java.util.List;
import java.util.Set;

import sh.libre.scim.core.ScimDispatcher;
import sh.libre.scim.core.UserAdapter;

public class ScimLdapStorageMapper implements LDAPStorageMapper {

    private static final Logger LOGGER = Logger.getLogger(ScimLdapStorageMapper.class);

    private final ScimDispatcher dispatcher;

    public ScimLdapStorageMapper(ScimDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Override
    public void onImportUserFromLDAP(LDAPObject ldapUser, UserModel user, RealmModel realm, boolean isCreate) {
        LOGGER.infof("onImportUserFromLDAP user=%s isCreate=%s", user.getUsername(), isCreate);
        if (isCreate) {
            dispatcher.run(ScimDispatcher.SCOPE_USER, client -> client.create(UserAdapter.class, user));
        } else {
            dispatcher.run(ScimDispatcher.SCOPE_USER, client -> client.replace(UserAdapter.class, user));
        }
    }

    @Override
    public void onRegisterUserToLDAP(LDAPObject ldapUser, UserModel localUser, RealmModel realm) {
        // no-op: Keycloak->LDAP direction is not our concern
    }

    @Override
    public UserModel proxy(LDAPObject ldapUser, UserModel delegate, RealmModel realm) {
        return delegate;
    }

    @Override
    public void beforeLDAPQuery(LDAPQuery query) {
        // no-op
    }

    @Override
    public LDAPStorageProvider getLdapProvider() {
        return null;
    }

    @Override
    public boolean onAuthenticationFailure(LDAPObject ldapUser, UserModel user, AuthenticationException ldapException, RealmModel realm) {
        return false;
    }

    @Override
    public List<UserModel> getGroupMembers(RealmModel realm, GroupModel group, int firstResult, int maxResults) {
        return null;
    }

    @Override
    public List<UserModel> getRoleMembers(RealmModel realm, RoleModel role, int firstResult, int maxResults) {
        return null;
    }

    @Override
    public SynchronizationResult syncDataFromFederationProviderToKeycloak(RealmModel realm) {
        return new SynchronizationResult();
    }

    @Override
    public SynchronizationResult syncDataFromKeycloakToFederationProvider(RealmModel realm) {
        return new SynchronizationResult();
    }

    @Override
    public Set<String> getUserAttributes() {
        return Set.of();
    }

    @Override
    public Set<String> mandatoryAttributeNames() {
        return Set.of();
    }

    @Override
    public void close() {
        // no-op
    }
}

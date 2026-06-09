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
import java.time.Instant;
import java.util.List;
import java.util.Set;

import sh.libre.scim.core.GroupAdapter;
import sh.libre.scim.core.ScimDispatcher;
import sh.libre.scim.core.UserAdapter;

public class ScimLdapStorageMapper implements LDAPStorageMapper {

    public static final String LAST_SEEN_ATTRIBUTE = "ldap-federation-last-seen";

    private static final Logger LOGGER = Logger.getLogger(ScimLdapStorageMapper.class);

    private final ScimDispatcher dispatcher;

    public ScimLdapStorageMapper(ScimDispatcher dispatcher) {
        this.dispatcher = dispatcher;
    }

    @Override
    public void onImportUserFromLDAP(LDAPObject ldapUser, UserModel user, RealmModel realm, boolean isCreate) {
        LOGGER.debugf("onImportUserFromLDAP user=%s isCreate=%s", user.getUsername(), isCreate);
        user.setSingleAttribute(LAST_SEEN_ATTRIBUTE, Instant.now().toString());

        // Async dispatch: capture user id by value (the UserModel reference
        // is bound to the import-thread session), let workers re-fetch in
        // their own session. This pulls the SCIM HTTP cost off the
        // user-import thread, which was otherwise serializing the entire
        // LDAP federation sync at the rate of one SCIM POST per user
        // (~43ms each in measurement). With 8 workers, throughput on
        // 10k-user syncs goes from ~22 users/sec to ~150-180 users/sec.
        String userId = user.getId();
        if (isCreate) {
            dispatcher.runAsync(ScimDispatcher.SCOPE_USER, (client, workerSession) -> {
                var u = workerSession.users().getUserById(workerSession.getContext().getRealm(), userId);
                if (u != null) client.create(UserAdapter::new, u);
            });
        } else {
            dispatcher.runAsync(ScimDispatcher.SCOPE_USER, (client, workerSession) -> {
                var u = workerSession.users().getUserById(workerSession.getContext().getRealm(), userId);
                if (u != null) client.replace(UserAdapter::new, u);
            });
        }

        // Propagate the user's current group memberships. LDAP-driven
        // membership changes never fire GROUP_MEMBERSHIP admin events, so this
        // hook is the only signal. Additions only; idempotent, so re-asserting
        // on every import is safe (see the design doc). Runs on SCOPE_GROUP,
        // independent of the user dispatch above — interoperates only with
        // components configured for both user and group propagation.
        dispatcher.runAsync(ScimDispatcher.SCOPE_GROUP, (client, workerSession) -> {
            var u = workerSession.users().getUserById(workerSession.getContext().getRealm(), userId);
            if (u == null) return;
            u.getGroupsStream().forEach(group ->
                client.ensureGroupMembership(GroupAdapter::new, group.getId(), userId));
        });
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
        // Must return an empty list, not null. Keycloak's
        // LDAPStorageProvider.getGroupMembersStream iterates over every
        // attached mapper's getGroupMembers and combines the results;
        // a null return value NPEs the stream pipeline.
        return List.of();
    }

    @Override
    public List<UserModel> getRoleMembers(RealmModel realm, RoleModel role, int firstResult, int maxResults) {
        // Same constraint as getGroupMembers — return empty, not null.
        return List.of();
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
        // Releases the dispatcher's cached ScimClients. Important: at scale
        // (10k+ user import) the dispatcher accumulates one client per SCIM
        // provider component, each holding an Apache HttpClient pool.
        dispatcher.close();
    }
}

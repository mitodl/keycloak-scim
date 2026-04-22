package sh.libre.scim.ldap;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.storage.ldap.idm.model.LDAPObject;
import org.mockito.ArgumentCaptor;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;
import sh.libre.scim.core.ScimClient;
import sh.libre.scim.core.ScimDispatcher;
import sh.libre.scim.core.UserAdapter;

import java.util.function.Consumer;

import static org.junit.jupiter.api.Assertions.assertSame;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;

@ExtendWith(MockitoExtension.class)
class ScimLdapStorageMapperTest {

    @Mock ScimDispatcher dispatcher;
    @Mock UserModel user;
    @Mock RealmModel realm;
    @Mock LDAPObject ldapObject;

    private ScimLdapStorageMapper mapper;

    @BeforeEach
    void setUp() {
        mapper = new ScimLdapStorageMapper(dispatcher);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void onImportRoutesCreateWhenIsCreateTrue() {
        mapper.onImportUserFromLDAP(ldapObject, user, realm, true);

        ArgumentCaptor<Consumer> captor = ArgumentCaptor.forClass(Consumer.class);
        verify(dispatcher).run(eq(ScimDispatcher.SCOPE_USER), captor.capture());

        var client = mock(ScimClient.class);
        ((Consumer<ScimClient>) captor.getValue()).accept(client);
        verify(client).create(UserAdapter.class, user);
    }

    @Test
    @SuppressWarnings({"unchecked", "rawtypes"})
    void onImportRoutesReplaceWhenIsCreateFalse() {
        mapper.onImportUserFromLDAP(ldapObject, user, realm, false);

        ArgumentCaptor<Consumer> captor = ArgumentCaptor.forClass(Consumer.class);
        verify(dispatcher).run(eq(ScimDispatcher.SCOPE_USER), captor.capture());

        var client = mock(ScimClient.class);
        ((Consumer<ScimClient>) captor.getValue()).accept(client);
        verify(client).replace(UserAdapter.class, user);
    }

    @Test
    void onRegisterUserToLDAPIsNoOp() {
        mapper.onRegisterUserToLDAP(ldapObject, user, realm);
        verifyNoInteractions(dispatcher);
    }

    @Test
    void proxyReturnsDelegateUnchanged() {
        var delegate = mock(UserModel.class);
        assertSame(delegate, mapper.proxy(ldapObject, delegate, realm));
        verifyNoInteractions(dispatcher);
    }

    @Test
    void getLdapProviderReturnsNull() {
        org.junit.jupiter.api.Assertions.assertNull(mapper.getLdapProvider());
    }
}

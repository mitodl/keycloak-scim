package sh.libre.scim.core;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.keycloak.component.ComponentModel;
import org.keycloak.models.KeycloakContext;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.concurrent.atomic.AtomicBoolean;
import java.util.stream.Stream;

import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class ScimDispatcherTest {

    @Mock KeycloakSession session;
    @Mock KeycloakContext context;
    @Mock RealmModel realm;
    @Mock ComponentModel unrelatedComponent;

    private ScimDispatcher dispatcher;

    @BeforeEach
    void setUp() {
        when(session.getContext()).thenReturn(context);
        when(context.getRealm()).thenReturn(realm);
        dispatcher = new ScimDispatcher(session);
    }

    @Test
    void runDoesNothingWhenNoProvidersConfigured() {
        when(realm.getComponentsStream()).thenReturn(Stream.empty());

        var invoked = new AtomicBoolean(false);
        dispatcher.run(ScimDispatcher.SCOPE_USER, client -> invoked.set(true));

        assertFalse(invoked.get(), "consumer should not be invoked when no providers are configured");
    }

    @Test
    void runDoesNothingWhenProviderIdDoesNotMatch() {
        when(unrelatedComponent.getProviderId()).thenReturn("some-other-provider");
        when(realm.getComponentsStream()).thenReturn(Stream.of(unrelatedComponent));

        var invoked = new AtomicBoolean(false);
        dispatcher.run(ScimDispatcher.SCOPE_USER, client -> invoked.set(true));

        assertFalse(invoked.get(), "consumer should not be invoked for non-SCIM providers");
    }
}

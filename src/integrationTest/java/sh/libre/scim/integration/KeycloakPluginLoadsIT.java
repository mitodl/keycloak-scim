package sh.libre.scim.integration;

import dasniko.testcontainers.keycloak.KeycloakContainer;
import org.junit.jupiter.api.Test;
import org.keycloak.representations.info.ServerInfoRepresentation;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;

import java.io.File;
import java.util.List;

import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * Smoke test: a real Keycloak container starts with the shaded plugin JAR
 * mounted into /opt/keycloak/providers/, and the SCIM storage SPI and the
 * LDAP storage mapper factory both register without errors.
 *
 * Container startup takes ~30s. The Keycloak image (~500MB) is pulled on
 * first run and cached locally afterwards.
 *
 * To run: ./gradlew integrationTest
 */
@Testcontainers
class KeycloakPluginLoadsIT {

    private static final File PLUGIN_JAR = new File(
        System.getProperty(
            "keycloak.plugin.jar",
            "build/libs/keycloak-scim-1.0-SNAPSHOT-all.jar"
        )
    );

    @Container
    static final KeycloakContainer keycloak =
        new KeycloakContainer("quay.io/keycloak/keycloak:25.0.6")
            .withProviderLibsFrom(List.of(PLUGIN_JAR));

    @Test
    void keycloakStartsWithPluginJarLoaded() {
        assertTrue(keycloak.isRunning(), "Keycloak container should be running");
        assertNotNull(keycloak.getAuthServerUrl(), "auth server URL should be exposed");
    }

    @Test
    void scimStorageProviderFactoryIsRegistered() {
        var admin = keycloak.getKeycloakAdminClient();
        ServerInfoRepresentation info = admin.serverInfo().getInfo();

        var userStorageProviders = info.getComponentTypes()
            .get("org.keycloak.storage.UserStorageProvider");
        assertNotNull(userStorageProviders, "UserStorageProvider component type list should be present");
        assertTrue(
            userStorageProviders.stream().anyMatch(c -> "scim".equals(c.getId())),
            "SCIM UserStorageProvider factory ('scim') should be registered"
        );
    }

    @Test
    void scimLdapStorageMapperFactoryIsRegistered() {
        var admin = keycloak.getKeycloakAdminClient();
        ServerInfoRepresentation info = admin.serverInfo().getInfo();

        var ldapMappers = info.getComponentTypes()
            .get("org.keycloak.storage.ldap.mappers.LDAPStorageMapper");
        assertNotNull(ldapMappers, "LDAPStorageMapper component type list should be present");
        assertTrue(
            ldapMappers.stream().anyMatch(c -> "scim-ldap-sync".equals(c.getId())),
            "SCIM LDAP mapper factory ('scim-ldap-sync') should be registered"
        );
    }
}

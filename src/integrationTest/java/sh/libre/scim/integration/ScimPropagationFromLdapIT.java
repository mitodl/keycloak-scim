package sh.libre.scim.integration;

import com.github.tomakehurst.wiremock.WireMockServer;
import dasniko.testcontainers.keycloak.KeycloakContainer;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.common.util.MultivaluedHashMap;
import org.keycloak.representations.idm.ComponentRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;

import java.io.File;
import java.util.List;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;

/**
 * End-to-end: user exists only in LDAP; admin REST user search triggers Keycloak's
 * federation lazy-import, which fires onImportUserFromLDAP on our mapper, which
 * propagates the user to a SCIM sink.
 */
class ScimPropagationFromLdapIT {

    private static final String REALM = "ldap-test";
    private static final File PLUGIN_JAR = new File(
        System.getProperty(
            "keycloak.plugin.jar",
            "build/libs/keycloak-scim-1.0-SNAPSHOT-all.jar"
        )
    );

    private static final Network network = Network.newNetwork();

    private static final GenericContainer<?> openldap =
        new GenericContainer<>("osixia/openldap:1.5.0")
            .withEnv("LDAP_ORGANISATION", "Test")
            .withEnv("LDAP_DOMAIN", "test.local")
            .withEnv("LDAP_ADMIN_PASSWORD", "adminpassword")
            .withClasspathResourceMapping(
                "seed.ldif",
                "/container/service/slapd/assets/config/bootstrap/ldif/custom/seed.ldif",
                BindMode.READ_ONLY)
            .withExposedPorts(389)
            .withNetwork(network)
            .withNetworkAliases("openldap");

    private static final KeycloakContainer keycloak =
        new KeycloakContainer("quay.io/keycloak/keycloak:25.0.6")
            .withProviderLibsFrom(List.of(PLUGIN_JAR))
            .withNetwork(network);

    private static WireMockServer wireMock;

    @BeforeAll
    static void setUp() {
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
        Testcontainers.exposeHostPorts(wireMock.port());

        openldap.start();
        keycloak.start();
    }

    @AfterAll
    static void tearDown() {
        if (keycloak != null) keycloak.stop();
        if (openldap != null) openldap.stop();
        if (wireMock != null) wireMock.stop();
    }

    @Test
    void lazyLdapImportTriggersScimPost() {
        wireMock.stubFor(post(urlPathEqualTo("/Users"))
            .willReturn(aResponse()
                .withStatus(201)
                .withHeader("Content-Type", "application/scim+json")
                .withBody("""
                    {
                      "id": "ext-alice",
                      "userName": "alice",
                      "schemas": ["urn:ietf:params:scim:schemas:core:2.0:User"]
                    }""")));

        Keycloak admin = keycloak.getKeycloakAdminClient();
        var realmRep = new RealmRepresentation();
        realmRep.setRealm(REALM);
        realmRep.setEnabled(true);
        admin.realms().create(realmRep);
        RealmResource realm = admin.realm(REALM);

        addScimStorageProvider(realm);
        String ldapId = addLdapFederation(realm);
        attachScimMapper(realm, ldapId);

        // Search triggers federation lazy-import.
        realm.users().search("alice", 0, 10);

        await().atMost(20, SECONDS).untilAsserted(() ->
            wireMock.verify(postRequestedFor(urlPathEqualTo("/Users"))
                .withRequestBody(matchingJsonPath("$.userName", equalTo("alice"))))
        );
    }

    private void addScimStorageProvider(RealmResource realm) {
        var scim = new ComponentRepresentation();
        scim.setName("test-scim");
        scim.setProviderType("org.keycloak.storage.UserStorageProvider");
        scim.setProviderId("scim");
        var cfg = new MultivaluedHashMap<String, String>();
        cfg.putSingle("endpoint", "http://host.testcontainers.internal:" + wireMock.port());
        cfg.putSingle("auth-mode", "NONE");
        cfg.putSingle("content-type", "application/scim+json");
        cfg.putSingle("propagation-user", "true");
        cfg.putSingle("propagation-group", "false");
        cfg.putSingle("enabled", "true");
        scim.setConfig(cfg);
        try (Response r = realm.components().add(scim)) {
            if (r.getStatus() >= 400) {
                throw new IllegalStateException("SCIM provider create failed: " + r.getStatus());
            }
        }
    }

    private String addLdapFederation(RealmResource realm) {
        var ldap = new ComponentRepresentation();
        ldap.setName("test-ldap");
        ldap.setProviderType("org.keycloak.storage.UserStorageProvider");
        ldap.setProviderId("ldap");
        var cfg = new MultivaluedHashMap<String, String>();
        cfg.putSingle("connectionUrl", "ldap://openldap:389");
        cfg.putSingle("bindDn", "cn=admin,dc=test,dc=local");
        cfg.putSingle("bindCredential", "adminpassword");
        cfg.putSingle("usersDn", "ou=users,dc=test,dc=local");
        cfg.putSingle("userObjectClasses", "inetOrgPerson, organizationalPerson");
        cfg.putSingle("rdnLDAPAttribute", "uid");
        cfg.putSingle("uuidLDAPAttribute", "entryUUID");
        cfg.putSingle("usernameLDAPAttribute", "uid");
        cfg.putSingle("editMode", "READ_ONLY");
        cfg.putSingle("importEnabled", "true");
        cfg.putSingle("syncRegistrations", "false");
        cfg.putSingle("vendor", "other");
        cfg.putSingle("authType", "simple");
        cfg.putSingle("searchScope", "1");
        ldap.setConfig(cfg);
        try (Response r = realm.components().add(ldap)) {
            if (r.getStatus() >= 400) {
                throw new IllegalStateException("LDAP federation create failed: " + r.getStatus());
            }
            var location = r.getLocation();
            return location.getPath().substring(location.getPath().lastIndexOf('/') + 1);
        }
    }

    private void attachScimMapper(RealmResource realm, String ldapId) {
        var mapper = new ComponentRepresentation();
        mapper.setName("test-scim-mapper");
        mapper.setProviderType("org.keycloak.storage.ldap.mappers.LDAPStorageMapper");
        mapper.setProviderId("scim-ldap-sync");
        mapper.setParentId(ldapId);
        try (Response r = realm.components().add(mapper)) {
            if (r.getStatus() >= 400) {
                throw new IllegalStateException("Mapper create failed: " + r.getStatus());
            }
        }
    }
}

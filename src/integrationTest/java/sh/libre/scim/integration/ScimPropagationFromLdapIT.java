package sh.libre.scim.integration;

import com.github.tomakehurst.wiremock.WireMockServer;
import dasniko.testcontainers.keycloak.KeycloakContainer;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
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
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

/**
 * End-to-end scenarios exercising the scim-ldap-sync mapper across the full stack:
 * Keycloak 25.0.6 + osixia/openldap + an embedded WireMock SCIM sink.
 *
 * Each test creates a fresh realm so state (realms, components, JPA mappings
 * scoped by realmId+componentId) does not leak between scenarios. WireMock
 * stubs are reset per test.
 *
 * Known gap: during the initial lazy import, our mapper's onImportUserFromLDAP
 * fires in the same iteration as the built-in user-attribute mappers, and
 * email/firstName/lastName are NOT yet populated on the UserModel at that
 * point. Setting LDAPStorageMapperFactory#order() does not reorder this
 * initial pass in Keycloak 25. Tests that need a fully-populated UserModel
 * therefore use the admin-REST create/update path (which goes through
 * ScimEventListenerProvider, not our mapper). The primary LDAP path
 * (lazyImportTriggersScimPost etc.) exercises the isCreate=true flow.
 */
class ScimPropagationFromLdapIT {

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
    private static Keycloak admin;

    @BeforeAll
    static void setUp() {
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
        Testcontainers.exposeHostPorts(wireMock.port());
        openldap.start();
        keycloak.start();
        admin = keycloak.getKeycloakAdminClient();
    }

    @AfterAll
    static void tearDown() {
        if (keycloak != null) keycloak.stop();
        if (openldap != null) openldap.stop();
        if (wireMock != null) wireMock.stop();
    }

    @BeforeEach
    void resetWireMock() {
        wireMock.resetAll();
    }

    // ---------- scenarios ----------

    @Test
    void lazyImportTriggersScimPost() {
        stubScimCreateOk();
        var r = newRealmWithScimAndLdap();

        r.realm.users().search("alice", 0, 10);

        awaitPostFor("alice");
    }

    @Test
    void lazyImportIsIdempotent() {
        stubScimCreateOk();
        var r = newRealmWithScimAndLdap();

        r.realm.users().search("alice", 0, 10);
        awaitPostFor("alice");

        // Second search after the user is already imported should NOT
        // produce another POST — ScimClient.create skips when a mapping exists.
        r.realm.users().search("alice", 0, 10);

        // Give any stray async propagation a window to misbehave, then assert
        // total count is still 1.
        sleepQuietly(2);
        int posts = wireMock.countRequestsMatching(
            postRequestedFor(urlPathEqualTo("/Users")).build()
        ).getCount();
        assertEquals(1, posts, "second lazy import must not create a duplicate SCIM resource");
    }

    @Test
    void fullSyncPropagatesAllLdapUsers() {
        stubScimCreateOk();
        var r = newRealmWithScimAndLdap();

        r.realm.userStorage().syncUsers(r.ldapId, "triggerFullSync");

        awaitPostFor("alice");
        awaitPostFor("bob");
    }

    @Test
    void scimSinkFailureDoesNotBlockLdapImport() {
        // No POST stub — WireMock will return 404 for /Users.
        // ScimDispatcher.runOne catches exceptions from the SCIM call, so the
        // LDAP import path must still produce a local Keycloak UserModel.
        var r = newRealmWithScimAndLdap();

        var found = r.realm.users().search("alice", 0, 10);
        assertEquals(1, found.size(), "alice must be imported locally even when SCIM sink is unavailable");
        assertEquals("alice", found.get(0).getUsername());
    }


    // ---------- helpers ----------

    private record TestRealm(String name, String ldapId, RealmResource realm) {}

    private TestRealm newRealmWithScimAndLdap() {
        return newRealmWithScimAndLdapAndConfig(cfg -> {});
    }

    private TestRealm newRealmWithScimAndLdapAndConfig(
            java.util.function.Consumer<MultivaluedHashMap<String, String>> scimCfgCustomizer) {
        String realmName = "it-" + UUID.randomUUID().toString().substring(0, 8);
        var realmRep = new RealmRepresentation();
        realmRep.setRealm(realmName);
        realmRep.setEnabled(true);
        admin.realms().create(realmRep);
        RealmResource realm = admin.realm(realmName);

        addScimStorageProvider(realm, scimCfgCustomizer);
        String ldapId = addLdapFederation(realm);
        // Order matters: attribute mappers must run before our scim-ldap-sync mapper
        // so the UserModel has email/firstName/lastName set by the time
        // onImportUserFromLDAP fires on us.
        addLdapAttributeMapper(realm, ldapId, "email", "email", "mail");
        addLdapAttributeMapper(realm, ldapId, "firstName", "firstName", "givenName");
        addLdapAttributeMapper(realm, ldapId, "lastName", "lastName", "sn");
        attachScimMapper(realm, ldapId);
        return new TestRealm(realmName, ldapId, realm);
    }

    private void addLdapAttributeMapper(
            RealmResource realm, String ldapId, String name, String userAttr, String ldapAttr) {
        var mapper = new ComponentRepresentation();
        mapper.setName(name);
        mapper.setProviderType("org.keycloak.storage.ldap.mappers.LDAPStorageMapper");
        mapper.setProviderId("user-attribute-ldap-mapper");
        mapper.setParentId(ldapId);
        var cfg = new MultivaluedHashMap<String, String>();
        cfg.putSingle("user.model.attribute", userAttr);
        cfg.putSingle("ldap.attribute", ldapAttr);
        cfg.putSingle("read.only", "true");
        cfg.putSingle("always.read.value.from.ldap", "true");
        cfg.putSingle("is.mandatory.in.ldap", "false");
        mapper.setConfig(cfg);
        try (Response r = realm.components().add(mapper)) {
            if (r.getStatus() >= 400) {
                throw new IllegalStateException("LDAP attr mapper " + name + " failed: " + r.getStatus());
            }
        }
    }

    private void addScimStorageProvider(
            RealmResource realm,
            java.util.function.Consumer<MultivaluedHashMap<String, String>> customizer) {
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
        customizer.accept(cfg);
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
            assertNotNull(location, "expected Location header after LDAP component create");
            String path = location.getPath();
            return path.substring(path.lastIndexOf('/') + 1);
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

    private void stubScimCreateOk() {
        wireMock.stubFor(post(urlPathEqualTo("/Users"))
            .willReturn(aResponse()
                .withStatus(201)
                .withHeader("Content-Type", "application/scim+json")
                .withBody("""
                    {
                      "id": "ext-%s",
                      "userName": "placeholder",
                      "schemas": ["urn:ietf:params:scim:schemas:core:2.0:User"]
                    }""".formatted(UUID.randomUUID()))));
    }

    private void awaitPostFor(String userName) {
        await().atMost(20, SECONDS).untilAsserted(() ->
            wireMock.verify(postRequestedFor(urlPathEqualTo("/Users"))
                .withRequestBody(matchingJsonPath("$.userName", equalTo(userName))))
        );
    }

    private static void sleepQuietly(int seconds) {
        try {
            Thread.sleep(seconds * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

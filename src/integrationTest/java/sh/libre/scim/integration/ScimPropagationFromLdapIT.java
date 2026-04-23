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
import org.keycloak.representations.idm.UserRepresentation;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;

import javax.naming.Context;
import javax.naming.NamingException;
import javax.naming.directory.InitialDirContext;
import java.io.File;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import java.util.UUID;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.deleteRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.putRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
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
    void ldapModificationTriggersScimPutViaChangedUsersSync() throws Exception {
        // Design-doc scenario 2: modify a user in LDAP, run triggerChangedUsersSync,
        // expect a SCIM PUT via the isCreate=false code path.
        stubScimCreateOk();
        stubScimUpdateOk();
        var r = newRealmWithScimAndLdap();

        // Initial import: POST path.
        r.realm.users().search("alice", 0, 10);
        awaitPostFor("alice");

        try {
            // Bump alice's modifyTimestamp so changedUsersSync picks her up.
            modifyLdapAttribute("uid=alice,ou=users,dc=test,dc=local", "sn", "Anderson-Modified");

            r.realm.userStorage().syncUsers(r.ldapId, "triggerChangedUsersSync");

            try {
                await().atMost(20, SECONDS).untilAsserted(() -> {
                    int puts = wireMock.countRequestsMatching(
                        putRequestedFor(urlPathMatching("/Users/.*")).build()
                    ).getCount();
                    assertTrue(puts >= 1,
                        "expected at least one SCIM PUT after LDAP modify + changedUsersSync, got " + puts);
                });
            } catch (Throwable t) {
                System.out.println("=== Keycloak mapper log lines ===");
                keycloak.getLogs().lines()
                    .filter(l -> l.contains("onImportUserFromLDAP")
                        || l.contains("ScimLdapStorageMapper")
                        || l.contains("ScimClient")
                        || l.contains("ScimDispatcher"))
                    .forEach(System.out::println);
                throw t;
            }
        } finally {
            // Restore alice's sn so later tests see the original state.
            modifyLdapAttribute("uid=alice,ou=users,dc=test,dc=local", "sn", "Anderson");
        }
    }

    @Test
    void ldapDeletionGapIsDocumented() throws Exception {
        // Design-doc scenario 4. Empirically verified on Keycloak 25.0.6:
        // when a user is removed from LDAP and triggerFullSync is run,
        //   * Keycloak's LDAP federation does not automatically delete the
        //     local UserModel (the default sync strategy imports but does
        //     not reconcile removals), and
        //   * consequently no admin USER/DELETE event reaches mitodl's
        //     existing ScimEventListenerProvider, so no SCIM DELETE hits
        //     the sink.
        //
        // Closing the gap requires either a diff-based reconciler, a
        // custom sync strategy that deletes local users missing from
        // LDAP, or an LDAPStorageMapper-level hook. Until then, deployments
        // that expect LDAP deletions to propagate to SCIM must rely on an
        // external reconciliation loop.
        //
        // This test pins the current behavior: 0 SCIM DELETEs. When the
        // gap is closed and deletes start reaching the sink, this test
        // will turn red — at that point flip the assertion to a positive
        // one and update the Status section of the design doc.
        stubScimCreateOk();
        stubScimDeleteOk();
        var r = newRealmWithScimAndLdap();
        enableScimEventListener(r.realm);

        r.realm.users().search("alice", 0, 10);
        awaitPostFor("alice");

        deleteLdapEntry("uid=alice,ou=users,dc=test,dc=local");
        try {
            r.realm.userStorage().syncUsers(r.ldapId, "triggerFullSync");

            // Give any async propagation a window, then assert the gap.
            sleepQuietly(3);
            int deletes = wireMock.countRequestsMatching(
                deleteRequestedFor(urlPathMatching("/Users/.*")).build()
            ).getCount();
            assertEquals(0, deletes,
                "gap documented: LDAP-triggered deletion does not propagate to SCIM. "
                + "If this fails with deletes > 0, the reconciliation gap has been closed — "
                + "invert the assertion and update docs/ldap-federation-support.md Status.");
        } finally {
            // Restore alice so later tests still find her in LDAP. The OpenLDAP
            // container is shared across the class; deletions persist.
            reAddAlice();
        }
    }

    private void reAddAlice() throws NamingException {
        var ctx = new InitialDirContext(newLdapEnv());
        try {
            var attrs = new javax.naming.directory.BasicAttributes();
            var oc = new javax.naming.directory.BasicAttribute("objectClass");
            oc.add("inetOrgPerson");
            oc.add("organizationalPerson");
            oc.add("person");
            oc.add("top");
            attrs.put(oc);
            attrs.put("cn", "Alice Anderson");
            attrs.put("sn", "Anderson");
            attrs.put("givenName", "Alice");
            attrs.put("uid", "alice");
            attrs.put("mail", "alice@test.local");
            attrs.put("userPassword", "alicepass");
            ctx.createSubcontext("uid=alice,ou=users,dc=test,dc=local", attrs);
        } finally {
            ctx.close();
        }
    }

    @Test
    void adminCreatedUserFiresScimPostViaEventListener() {
        stubScimCreateOk();
        var r = newRealmWithScimAndLdap();
        enableScimEventListener(r.realm);

        createAdminUser(r.realm, "charlie", "charlie@test.local");

        awaitPostFor("charlie");
    }

    @Test
    void adminUpdateOfUserFiresScimReplaceViaEventListener() {
        stubScimCreateOk();
        stubScimUpdateOk();
        var r = newRealmWithScimAndLdap();
        enableScimEventListener(r.realm);

        String userId = createAdminUser(r.realm, "dana", "dana@test.local");
        awaitPostFor("dana");

        var updated = r.realm.users().get(userId).toRepresentation();
        updated.setLastName("Updated");
        r.realm.users().get(userId).update(updated);

        await().atMost(20, SECONDS).untilAsserted(() -> {
            int puts = wireMock.countRequestsMatching(
                putRequestedFor(urlPathMatching("/Users/.*")).build()
            ).getCount();
            assertTrue(puts >= 1, "expected SCIM PUT after admin update, got " + puts);
        });
    }

    @Test
    void usernameSourceEmailEmitsEmailAsScimUserName() {
        stubScimCreateOk();
        var r = newRealmWithScimAndLdapAndConfig(cfg ->
            cfg.putSingle("username-source", "email")
        );
        enableScimEventListener(r.realm);

        createAdminUser(r.realm, "erin", "erin@test.local");

        await().atMost(20, SECONDS).untilAsserted(() -> {
            var posts = wireMock.getAllServeEvents().stream()
                .filter(e -> e.getRequest().getUrl().startsWith("/Users")
                    && "POST".equals(e.getRequest().getMethod().getName()))
                .toList();
            assertTrue(posts.size() >= 1, "expected a SCIM POST");
            String body = posts.get(0).getRequest().getBodyAsString();
            assertTrue(body.contains("\"userName\":\"erin@test.local\""),
                "userName must be email with username-source=email, body was: " + body);
        });
    }

    @Test
    void adminDeleteOfScimBackedUserFiresScimDelete() {
        // Previously pinned as a gap (adminDeleteGapIsDocumented) because the
        // listener called getUser(userId) post-commit, got null, and NPEd before
        // reaching ScimClient.delete. Now closed: the DELETE handler uses
        // event.getUserId() directly and no longer re-fetches.
        stubScimCreateOk();
        stubScimDeleteOk();
        var r = newRealmWithScimAndLdap();
        enableScimEventListener(r.realm);

        String userId = createAdminUser(r.realm, "frank", "frank@test.local");
        awaitPostFor("frank");

        r.realm.users().get(userId).remove();

        await().atMost(20, SECONDS).untilAsserted(() -> {
            int deletes = wireMock.countRequestsMatching(
                deleteRequestedFor(urlPathMatching("/Users/.*")).build()
            ).getCount();
            assertTrue(deletes >= 1,
                "expected at least one SCIM DELETE after admin user delete, got " + deletes);
        });
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
        // UserAdapter.apply(User) calls .get() on id/userName/displayName/active,
        // so all four must be present or the adapter throws and the mapping
        // never gets persisted.
        // The id must fit in the SCIM_RESOURCE.EXTERNAL_ID column (VARCHAR(36));
        // a bare UUID is exactly 36 characters.
        wireMock.stubFor(post(urlPathEqualTo("/Users"))
            .willReturn(aResponse()
                .withStatus(201)
                .withHeader("Content-Type", "application/scim+json")
                .withBody("""
                    {
                      "id": "%s",
                      "userName": "placeholder",
                      "displayName": "placeholder",
                      "active": true,
                      "schemas": ["urn:ietf:params:scim:schemas:core:2.0:User"]
                    }""".formatted(UUID.randomUUID()))));
    }

    private void stubScimDeleteOk() {
        wireMock.stubFor(delete(urlPathMatching("/Users/.*"))
            .willReturn(aResponse().withStatus(204)));
    }

    private String createAdminUser(RealmResource realm, String username, String email) {
        var user = new UserRepresentation();
        user.setUsername(username);
        user.setEmail(email);
        // ScimEventListenerProvider ignores events for users with unverified emails.
        user.setEmailVerified(true);
        user.setEnabled(true);
        try (Response resp = realm.users().create(user)) {
            if (resp.getStatus() >= 400) {
                throw new IllegalStateException("admin user create for " + username
                    + " failed: " + resp.getStatus());
            }
            String path = resp.getLocation().getPath();
            return path.substring(path.lastIndexOf('/') + 1);
        }
    }

    private void stubScimUpdateOk() {
        wireMock.stubFor(put(urlMatching("/Users/.*"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/scim+json")
                .withBody("""
                    {
                      "id": "ext-updated",
                      "userName": "placeholder",
                      "schemas": ["urn:ietf:params:scim:schemas:core:2.0:User"]
                    }""")));
    }

    private void enableScimEventListener(RealmResource realm) {
        var rep = realm.toRepresentation();
        var listeners = new ArrayList<String>();
        if (rep.getEventsListeners() != null) {
            listeners.addAll(rep.getEventsListeners());
        }
        if (!listeners.contains("scim")) {
            listeners.add("scim");
        }
        rep.setEventsListeners(listeners);
        realm.update(rep);
    }

    private void modifyLdapAttribute(String dn, String attr, String value) throws NamingException {
        var env = newLdapEnv();
        var ctx = new InitialDirContext(env);
        try {
            var mod = new javax.naming.directory.ModificationItem(
                javax.naming.directory.DirContext.REPLACE_ATTRIBUTE,
                new javax.naming.directory.BasicAttribute(attr, value));
            ctx.modifyAttributes(dn, new javax.naming.directory.ModificationItem[]{mod});
        } finally {
            ctx.close();
        }
    }

    private Hashtable<String, String> newLdapEnv() {
        var env = new Hashtable<String, String>();
        env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
        env.put(Context.PROVIDER_URL,
            "ldap://" + openldap.getHost() + ":" + openldap.getMappedPort(389));
        env.put(Context.SECURITY_AUTHENTICATION, "simple");
        env.put(Context.SECURITY_PRINCIPAL, "cn=admin,dc=test,dc=local");
        env.put(Context.SECURITY_CREDENTIALS, "adminpassword");
        return env;
    }

    private void deleteLdapEntry(String dn) throws NamingException {
        var ctx = new InitialDirContext(newLdapEnv());
        try {
            ctx.destroySubcontext(dn);
        } finally {
            ctx.close();
        }
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

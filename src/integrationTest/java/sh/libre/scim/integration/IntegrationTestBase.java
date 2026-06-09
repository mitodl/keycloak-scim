package sh.libre.scim.integration;

import com.github.tomakehurst.wiremock.WireMockServer;
import dasniko.testcontainers.keycloak.KeycloakContainer;
import jakarta.ws.rs.core.Response;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.keycloak.admin.client.Keycloak;
import org.keycloak.admin.client.resource.RealmResource;
import org.keycloak.common.util.MultivaluedHashMap;
import org.keycloak.representations.idm.ComponentRepresentation;
import org.keycloak.representations.idm.GroupRepresentation;
import org.keycloak.representations.idm.RealmRepresentation;
import org.keycloak.representations.idm.UserRepresentation;
import org.testcontainers.Testcontainers;
import org.testcontainers.containers.BindMode;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.Network;

import javax.naming.Context;
import javax.naming.NamingException;
import javax.naming.directory.BasicAttribute;
import javax.naming.directory.BasicAttributes;
import javax.naming.directory.DirContext;
import javax.naming.directory.InitialDirContext;
import javax.naming.directory.ModificationItem;
import java.io.File;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.util.ArrayList;
import java.util.Hashtable;
import java.util.List;
import java.util.UUID;
import java.util.function.Consumer;

import static com.github.tomakehurst.wiremock.client.WireMock.aResponse;
import static com.github.tomakehurst.wiremock.client.WireMock.delete;
import static com.github.tomakehurst.wiremock.client.WireMock.equalTo;
import static com.github.tomakehurst.wiremock.client.WireMock.matchingJsonPath;
import static com.github.tomakehurst.wiremock.client.WireMock.patch;
import static com.github.tomakehurst.wiremock.client.WireMock.post;
import static com.github.tomakehurst.wiremock.client.WireMock.postRequestedFor;
import static com.github.tomakehurst.wiremock.client.WireMock.put;
import static com.github.tomakehurst.wiremock.client.WireMock.urlMatching;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathEqualTo;
import static com.github.tomakehurst.wiremock.client.WireMock.urlPathMatching;
import static com.github.tomakehurst.wiremock.core.WireMockConfiguration.options;
import static java.util.concurrent.TimeUnit.SECONDS;
import static org.awaitility.Awaitility.await;
import static org.junit.jupiter.api.Assertions.assertNotNull;

/**
 * Shared scaffolding for end-to-end integration tests against the full stack:
 * Keycloak 25.0.6 + osixia/openldap + an embedded WireMock SCIM sink.
 *
 * <p>Lifecycle: containers + WireMock are started once per test class
 * (forkEvery=1 in the gradle task gives each subclass a fresh JVM, so
 * containers do not leak between classes). WireMock stubs are reset before
 * every test method.
 *
 * <p>Subclasses inherit the container fields, the admin client, and a
 * library of helpers covering realm/component setup, LDAP manipulation,
 * SCIM stub creation, and convenience assertions.
 *
 * <p>Known constraint of the LDAP import path: when our mapper's
 * onImportUserFromLDAP fires during the initial lazy import, the user's
 * email/firstName/lastName attributes are not yet populated on the
 * UserModel — those attribute mappers run as part of the same iteration.
 * Tests that need a fully-populated UserModel use the admin-REST
 * create/update path, which goes through ScimEventListenerProvider rather
 * than our mapper.
 */
public abstract class IntegrationTestBase {

    protected static final File PLUGIN_JAR = new File(
        System.getProperty(
            "keycloak.plugin.jar",
            "build/docker/keycloak-scim.jar"
        )
    );

    protected static final Network network = Network.newNetwork();

    protected static final GenericContainer<?> openldap =
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

    /** Overrideable via `-Dkeycloak.image=<image:tag>` so CI can run a matrix
     *  across supported Keycloak majors. Default tracks our minimum
     *  supported version. */
    protected static final String KEYCLOAK_IMAGE =
        System.getProperty("keycloak.image", "quay.io/keycloak/keycloak:25.0.6");

    protected static final KeycloakContainer keycloak =
        new KeycloakContainer(KEYCLOAK_IMAGE)
            .withProviderLibsFrom(List.of(PLUGIN_JAR))
            .withNetwork(network);

    protected static WireMockServer wireMock;
    protected static Keycloak admin;

    @BeforeAll
    static void setUpInfra() {
        wireMock = new WireMockServer(options().dynamicPort());
        wireMock.start();
        Testcontainers.exposeHostPorts(wireMock.port());
        openldap.start();
        keycloak.start();
        admin = AdminClients.forContainer(keycloak);
    }

    @AfterAll
    static void tearDownInfra() {
        if (keycloak != null) keycloak.stop();
        if (openldap != null) openldap.stop();
        if (wireMock != null) wireMock.stop();
    }

    @BeforeEach
    void resetStubs() {
        wireMock.resetAll();
    }

    // ---------- realm + component setup ----------

    /** Bundle of identifiers a test typically needs from a freshly-set-up realm. */
    protected record TestRealm(String name, String ldapId, RealmResource realm) {}

    protected TestRealm newRealmWithScimAndLdap() {
        return newRealmWithScimAndLdapAndConfig(cfg -> {});
    }

    protected TestRealm newRealmWithScimAndLdapAndConfig(
            Consumer<MultivaluedHashMap<String, String>> scimCfgCustomizer) {
        String realmName = "it-" + UUID.randomUUID().toString().substring(0, 8);
        var realmRep = new RealmRepresentation();
        realmRep.setRealm(realmName);
        realmRep.setEnabled(true);
        admin.realms().create(realmRep);
        RealmResource realm = admin.realm(realmName);

        addScimStorageProvider(realm, scimCfgCustomizer);
        String ldapId = addLdapFederation(realm);
        // Order matters: attribute mappers must run before our scim-ldap-sync
        // mapper so the UserModel has email/firstName/lastName set by the
        // time onImportUserFromLDAP fires on us. (Note: in practice the
        // initial lazy-import iteration still doesn't honor this; tests
        // requiring a fully-populated UserModel use the admin-REST path.)
        addLdapAttributeMapper(realm, ldapId, "email", "email", "mail");
        addLdapAttributeMapper(realm, ldapId, "firstName", "firstName", "givenName");
        addLdapAttributeMapper(realm, ldapId, "lastName", "lastName", "sn");
        attachScimMapper(realm, ldapId);
        return new TestRealm(realmName, ldapId, realm);
    }

    /**
     * Like {@link #newRealmWithScimAndLdapAndConfig}, but additionally attaches
     * a group-ldap-mapper to the LDAP provider so an LDAP user's group
     * memberships (seeded as {@code groupOfNames} entries under
     * {@code ou=groups,dc=test,dc=local}) materialize onto the imported
     * Keycloak UserModel via {@code getGroupsStream()}.
     */
    protected TestRealm newRealmWithScimAndLdapGroups(
            Consumer<MultivaluedHashMap<String, String>> scimCfgCustomizer) {
        TestRealm r = newRealmWithScimAndLdapAndConfig(scimCfgCustomizer);
        addLdapGroupMapper(r.realm(), r.ldapId());
        return r;
    }

    protected void addLdapGroupMapper(RealmResource realm, String ldapId) {
        var mapper = new ComponentRepresentation();
        mapper.setName("groups");
        mapper.setProviderType("org.keycloak.storage.ldap.mappers.LDAPStorageMapper");
        mapper.setProviderId("group-ldap-mapper");
        mapper.setParentId(ldapId);
        var cfg = new MultivaluedHashMap<String, String>();
        cfg.putSingle("groups.dn", "ou=groups,dc=test,dc=local");
        cfg.putSingle("membership.ldap.attribute", "member");
        cfg.putSingle("membership.attribute.type", "DN");
        cfg.putSingle("group.name.ldap.attribute", "cn");
        cfg.putSingle("group.object.classes", "groupOfNames");
        cfg.putSingle("mode", "READ_ONLY");
        cfg.putSingle("preserve.group.inheritance", "false");
        cfg.putSingle("membership.user.ldap.attribute", "uid");
        cfg.putSingle("groups.path", "/");
        cfg.putSingle("user.roles.retrieve.strategy", "LOAD_GROUPS_BY_MEMBER_ATTRIBUTE");
        mapper.setConfig(cfg);
        try (Response r = realm.components().add(mapper)) {
            if (r.getStatus() >= 400) {
                throw new IllegalStateException("LDAP group mapper create failed: " + r.getStatus());
            }
        }
    }

    /**
     * LDAP-only realm with no SCIM provider and no scim-ldap-sync mapper —
     * used by perf tests to measure Keycloak's pure federation-import cost
     * as a baseline, isolating plugin overhead.
     */
    protected TestRealm newRealmWithLdapOnly() {
        String realmName = "it-" + UUID.randomUUID().toString().substring(0, 8);
        var realmRep = new RealmRepresentation();
        realmRep.setRealm(realmName);
        realmRep.setEnabled(true);
        admin.realms().create(realmRep);
        RealmResource realm = admin.realm(realmName);

        String ldapId = addLdapFederation(realm);
        addLdapAttributeMapper(realm, ldapId, "email", "email", "mail");
        addLdapAttributeMapper(realm, ldapId, "firstName", "firstName", "givenName");
        addLdapAttributeMapper(realm, ldapId, "lastName", "lastName", "sn");
        return new TestRealm(realmName, ldapId, realm);
    }

    protected void addScimStorageProvider(
            RealmResource realm,
            Consumer<MultivaluedHashMap<String, String>> customizer) {
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

    protected String addLdapFederation(RealmResource realm) {
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

    protected void addLdapAttributeMapper(
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

    protected void attachScimMapper(RealmResource realm, String ldapId) {
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

    protected String createAdminUser(RealmResource realm, String username, String email) {
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

    /**
     * Allow arbitrary user attributes (e.g. {@code scim-skip}) to be set via
     * admin REST. Keycloak 25's declarative user profile rejects unknown
     * attributes by default; setting unmanagedAttributePolicy=ENABLED is the
     * standard way operators turn that gate off when running plugins that
     * use custom attributes. Tests of attribute-driven plugin behavior need
     * the same.
     */
    protected void enableUnmanagedUserAttributes(RealmResource realm) {
        var profile = realm.users().userProfile();
        var cfg = profile.getConfiguration();
        cfg.setUnmanagedAttributePolicy(
            org.keycloak.representations.userprofile.config.UPConfig.UnmanagedAttributePolicy.ENABLED);
        profile.update(cfg);
    }

    protected void enableScimEventListener(RealmResource realm) {
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

    // ---------- WireMock stubs ----------

    protected void stubScimUserCreateOk() {
        // UserAdapter.apply(User) calls .get() on id/userName/displayName/active,
        // so all four must be present or the adapter throws and the mapping
        // never gets persisted. The id must fit in the SCIM_RESOURCE.EXTERNAL_ID
        // column (VARCHAR(36)); a bare UUID is exactly 36 characters.
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

    protected void stubScimUserUpdateOk() {
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

    protected void stubScimUserDeleteOk() {
        wireMock.stubFor(delete(urlPathMatching("/Users/.*"))
            .willReturn(aResponse().withStatus(204)));
    }

    protected void stubScimGroupCreateOk() {
        // GroupAdapter.apply(Group) calls .get() on id and displayName.
        // The id (used as our external-id in the mapping table) must fit
        // the VARCHAR(36) column.
        wireMock.stubFor(post(urlPathEqualTo("/Groups"))
            .willReturn(aResponse()
                .withStatus(201)
                .withHeader("Content-Type", "application/scim+json")
                .withBody("""
                    {
                      "id": "%s",
                      "displayName": "placeholder",
                      "schemas": ["urn:ietf:params:scim:schemas:core:2.0:Group"]
                    }""".formatted(UUID.randomUUID()))));
    }

    protected void stubScimGroupUpdateOk() {
        wireMock.stubFor(put(urlMatching("/Groups/.*"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/scim+json")
                .withBody("""
                    {
                      "id": "ext-updated",
                      "displayName": "placeholder",
                      "schemas": ["urn:ietf:params:scim:schemas:core:2.0:Group"]
                    }""")));
    }

    protected void stubScimGroupDeleteOk() {
        wireMock.stubFor(delete(urlPathMatching("/Groups/.*"))
            .willReturn(aResponse().withStatus(204)));
    }

    protected void stubScimGroupPatchOk() {
        wireMock.stubFor(patch(urlMatching("/Groups/.*"))
            .willReturn(aResponse()
                .withStatus(200)
                .withHeader("Content-Type", "application/scim+json")
                .withBody("""
                    {
                      "id": "ext-patched",
                      "displayName": "placeholder",
                      "schemas": ["urn:ietf:params:scim:schemas:core:2.0:Group"]
                    }""")));
    }

    /** Polls until WireMock has seen at least one POST to /Users with the given userName. */
    protected void awaitUserPostFor(String userName) {
        await().atMost(20, SECONDS).untilAsserted(() ->
            wireMock.verify(postRequestedFor(urlPathEqualTo("/Users"))
                .withRequestBody(matchingJsonPath("$.userName", equalTo(userName))))
        );
    }

    /** Polls until WireMock has seen at least one POST to /Groups with the given displayName. */
    protected void awaitGroupPostFor(String displayName) {
        await().atMost(20, SECONDS).untilAsserted(() ->
            wireMock.verify(postRequestedFor(urlPathEqualTo("/Groups"))
                .withRequestBody(matchingJsonPath("$.displayName", equalTo(displayName))))
        );
    }

    protected String createGroup(RealmResource realm, String name) {
        var rep = new GroupRepresentation();
        rep.setName(name);
        try (Response resp = realm.groups().add(rep)) {
            if (resp.getStatus() >= 400) {
                throw new IllegalStateException("admin group create for " + name
                    + " failed: " + resp.getStatus());
            }
            String path = resp.getLocation().getPath();
            return path.substring(path.lastIndexOf('/') + 1);
        }
    }

    // ---------- LDAP manipulation ----------

    protected Hashtable<String, String> newLdapEnv() {
        var env = new Hashtable<String, String>();
        env.put(Context.INITIAL_CONTEXT_FACTORY, "com.sun.jndi.ldap.LdapCtxFactory");
        env.put(Context.PROVIDER_URL,
            "ldap://" + openldap.getHost() + ":" + openldap.getMappedPort(389));
        env.put(Context.SECURITY_AUTHENTICATION, "simple");
        env.put(Context.SECURITY_PRINCIPAL, "cn=admin,dc=test,dc=local");
        env.put(Context.SECURITY_CREDENTIALS, "adminpassword");
        return env;
    }

    protected void deleteLdapEntry(String dn) throws NamingException {
        var ctx = new InitialDirContext(newLdapEnv());
        try {
            ctx.destroySubcontext(dn);
        } finally {
            ctx.close();
        }
    }

    protected void modifyLdapAttribute(String dn, String attr, String value) throws NamingException {
        var ctx = new InitialDirContext(newLdapEnv());
        try {
            var mod = new ModificationItem(
                DirContext.REPLACE_ATTRIBUTE,
                new BasicAttribute(attr, value));
            ctx.modifyAttributes(dn, new ModificationItem[]{mod});
        } finally {
            ctx.close();
        }
    }

    /** Restores the seeded alice entry. Tests that delete alice should call this in a finally. */
    protected void reAddAlice() throws NamingException {
        var ctx = new InitialDirContext(newLdapEnv());
        try {
            var attrs = new BasicAttributes();
            var oc = new BasicAttribute("objectClass");
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

    // ---------- Reconciler endpoint ----------

    protected HttpResponse<String> postReconcile(
            String realmName, String componentId, long thresholdHours) throws Exception {
        var http = HttpClient.newHttpClient();
        return http.send(
            HttpRequest.newBuilder(URI.create(
                keycloak.getAuthServerUrl() + "/realms/" + realmName
                    + "/scim-reconcile/" + componentId
                    + "?thresholdHours=" + thresholdHours))
                .POST(HttpRequest.BodyPublishers.noBody())
                .build(),
            HttpResponse.BodyHandlers.ofString());
    }

    // ---------- generic ----------

    protected static void sleepQuietly(int seconds) {
        try {
            Thread.sleep(seconds * 1000L);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        }
    }
}

package sh.libre.scim.core;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.regex.Pattern;

import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.ws.rs.ProcessingException;

import de.captaingoldfish.scim.sdk.client.ScimClientConfig;
import de.captaingoldfish.scim.sdk.client.ScimRequestBuilder;
import de.captaingoldfish.scim.sdk.client.http.BasicAuth;
import de.captaingoldfish.scim.sdk.client.response.ServerResponse;
import de.captaingoldfish.scim.sdk.common.exceptions.ResponseException;
import de.captaingoldfish.scim.sdk.common.resources.ResourceNode;
import de.captaingoldfish.scim.sdk.common.response.ListResponse;

import org.jboss.logging.Logger;
import org.keycloak.component.ComponentModel;
import org.keycloak.connections.jpa.JpaConnectionProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RoleMapperModel;
import org.keycloak.storage.user.SynchronizationResult;
import sh.libre.scim.storage.ScimSynchronizationResult;

import com.google.common.net.HttpHeaders;

import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;


public class ScimClient {
    final protected Logger LOGGER = Logger.getLogger(ScimClient.class);
    final protected ScimRequestBuilder scimRequestBuilder;
    final protected RetryRegistry registry;
    final protected KeycloakSession session;
    final protected String contentType;
    final protected ComponentModel model;
    final protected String scimApplicationBaseUrl;
    final protected Map<String, String> defaultHeaders;
    final protected Map<String, String> expectedResponseHeaders;

    public ScimClient(ComponentModel model, KeycloakSession session) {
        this.model = model;
        this.contentType = model.get("content-type");
        this.session = session;
        this.scimApplicationBaseUrl = model.get("endpoint");
        this.defaultHeaders = new HashMap<>();
        this.expectedResponseHeaders = new HashMap<>();

        switch (model.get("auth-mode")) {
            case "BEARER":
                defaultHeaders.put(HttpHeaders.AUTHORIZATION,
                    BearerAuthentication(model.get("auth-pass")));
                break;
            case "BASIC_AUTH":
                defaultHeaders.put(HttpHeaders.AUTHORIZATION,
                    BasicAuthentication(model.get("auth-user"),
                                        model.get("auth-pass")));
                break;
        }

        defaultHeaders.put(HttpHeaders.CONTENT_TYPE, contentType);

        scimRequestBuilder = new ScimRequestBuilder(scimApplicationBaseUrl, genScimClientConfig());

        RetryConfig retryConfig = RetryConfig.custom()
            .maxAttempts(10)
            .intervalFunction(IntervalFunction.ofExponentialBackoff())
            .retryExceptions(ProcessingException.class)
            .build();

        registry = RetryRegistry.of(retryConfig);
    }

    protected String BasicAuthentication(String username, String password) {
        return  BasicAuth.builder()
        .username(model.get(username))
        .password(model.get(password))
        .build()
        .getAuthorizationHeaderValue();
    }

    protected ScimClientConfig genScimClientConfig() {
        return ScimClientConfig.builder()
        .httpHeaders(defaultHeaders)
        .connectTimeout(30)
        .requestTimeout(30)
        .socketTimeout(30)
        .expectedHttpResponseHeaders(expectedResponseHeaders)
        .hostnameVerifier((s, sslSession) -> true)
        .build();
    }

    protected String BearerAuthentication(String token) {
        return "Bearer " + token ;
    }

    protected String genScimUrl(String scimEndpoint, String resourcePath) {
        return "%s/%s/%s".formatted(scimApplicationBaseUrl,
                scimEndpoint,
                resourcePath);
    }


    protected EntityManager getEM() {
        return session.getProvider(JpaConnectionProvider.class).getEntityManager();
    }

    protected String getRealmId() {
        return session.getContext().getRealm().getId();
    }

    protected <M extends RoleMapperModel, S extends ResourceNode, A extends Adapter<M, S>> A getAdapter(
            Class<A> aClass) {
        try {
            return aClass.getDeclaredConstructor(KeycloakSession.class, String.class)
                    .newInstance(session, this.model.getId());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }
    private <S extends ResourceNode> List<S> fetchAllResources(String endpoint, Class<S> resourceClass) {
        List<S> allResources = new ArrayList<>();
        try {
            String listUrl = scimApplicationBaseUrl + "/" + endpoint;
            LOGGER.infof("Sending SCIM list request to URL: %s", listUrl);
            ServerResponse<ListResponse<S>> pageResponse = scimRequestBuilder
                .list(listUrl, resourceClass)
                .get()
                .sendRequest();
            LOGGER.info("Received response for list request: status=" + pageResponse.getHttpStatus() + ", success=" + pageResponse.isSuccess());
            if (pageResponse.isSuccess()) {
                ListResponse<S> page = pageResponse.getResource();
                allResources.addAll(page.getListedResources());
                LOGGER.infof("Fetched %d resources from response", allResources.size());
                // Note: Assuming Databricks returns all resources in one page or we take the first page
            } else {
                LOGGER.warnf("Failed to fetch resources: HTTP %d - %s", pageResponse.getHttpStatus(), pageResponse.getResponseBody());
            }
        } catch (Exception e) {
            LOGGER.error("Failed to fetch resources", e);
            LOGGER.errorf("Failed to fetch resources: %s", e.getMessage());
        }
        return allResources;
    }

    private <M extends RoleMapperModel, S extends ResourceNode, A extends Adapter<M, S>> boolean tryMapToExisting(Class<A> aClass, M kcModel) {
        var adapter = getAdapter(aClass);
        adapter.apply(kcModel);
        try {
            LOGGER.infof("Fetching all resources for client-side filtering for %s", adapter.getId());
            List<S> allResources = fetchAllResources(adapter.getSCIMEndpoint(), adapter.getResourceClass());
            LOGGER.infof("Fetched %d resources for client-side filtering", allResources.size());
            S existingResource = null;
            String targetEmail = "";
            String targetDisplayName = "";
            if (adapter instanceof UserAdapter userAdapter) {
                targetEmail = userAdapter.getEmail();
                LOGGER.infof("Target email for mapping: %s", targetEmail);
            } else if (adapter instanceof GroupAdapter groupAdapter) {
                targetDisplayName = groupAdapter.getDisplayName();
                LOGGER.infof("Target displayName for mapping: %s", targetDisplayName);
            }
            for (S resource : allResources) {
                boolean match = false;
                if (adapter instanceof UserAdapter && !targetEmail.isEmpty()) {
                    if (resource instanceof de.captaingoldfish.scim.sdk.common.resources.User user) {
                        var emails = user.getEmails();
                        if (emails != null) {
                            for (var email : emails) {
                                if (email.getValue().isPresent()) {
                                    String resEmail = email.getValue().get();
                                    LOGGER.debugf("Checking resource email: %s against target: %s", resEmail, targetEmail);
                                    if (targetEmail.equalsIgnoreCase(resEmail)) {
                                        match = true;
                                        break;
                                    }
                                }
                            }
                        }
                    }
                } else if (adapter instanceof GroupAdapter && !targetDisplayName.isEmpty()) {
                    if (resource instanceof de.captaingoldfish.scim.sdk.common.resources.Group group) {
                        String resDisplayName = group.getDisplayName().orElse("");
                        LOGGER.debugf("Checking resource displayName: %s against target: %s", resDisplayName, targetDisplayName);
                        if (targetDisplayName.equals(resDisplayName)) {
                            match = true;
                        }
                    }
                }
                if (match) {
                    existingResource = resource;
                    LOGGER.infof("Found existing resource via client filter: %s", existingResource.getId());
                    break;
                }
            }
            if (existingResource != null) {
                adapter.apply(existingResource);
                adapter.saveMapping();
                LOGGER.infof("Mapped to existing resource for %s", adapter.getId());
                // The mapping row is saved either way - that part succeeded and is worth keeping - but
                // report the outcome of the push honestly.
                return this.replace(aClass, kcModel);
            } else {
                LOGGER.infof("No existing resources found matching the criteria for %s", adapter.getId());
            }
        } catch (Exception e) {
            LOGGER.errorf("Failed to check for existing resource for %s: %s", adapter.getId(), e.getMessage(), e);
        }
        return false;
    }


    /**
     * Is this group within the configured {@code group-filter}?
     *
     * 🚨 This guard exists because {@code group-filter} was only ever applied by
     * {@code Adapter.getFilteredGroups()}, which feeds the PERIODIC SYNC. The event path
     * ({@code ScimEventListenerProvider}) takes the changed group straight from the admin event and
     * dispatches it, so ANY group membership change in the realm - not just in-scope ones - produced a
     * write against the SCIM endpoint. On sso.daikinlab.com that meant every one of the realm's groups
     * was reachable from a routine admin action, and while PATCH failed harmlessly on an id mismatch,
     * CREATE would have succeeded and put arbitrary Keycloak groups into the Databricks account.
     *
     * Enforcing it here rather than in the listener means every caller is covered - event, sync and
     * anything added later - and it is evaluated per component, which is correct when more than one
     * provider is configured.
     *
     * Users are not filtered: {@code group-filter} has never constrained them (which is how a test user
     * leaked during the ss-infra rehearsal). {@code propagation-user} is the control for those.
     */

    /**
     * Gives a GroupAdapter what it needs to compute a membership DELTA instead of a blind replace:
     * the group's current server-side members, and a way to resolve a Keycloak user to a remote id by
     * email when no local SCIM mapping row exists.
     *
     * Both are READS. Nothing here creates or modifies a user, so it is safe with
     * propagation-user = "false" - which is the configuration that made membership unresolvable before,
     * because the user adapter never runs and therefore no mapping rows are ever written.
     */
    private <M extends RoleMapperModel, S extends ResourceNode, A extends Adapter<M, S>> void primeGroupDelta(A adapter) {
        if (!(adapter instanceof GroupAdapter groupAdapter)) {
            return;
        }
        String externalId = adapter.getExternalId();
        try {
            var remoteGroups = fetchAllResources(adapter.getSCIMEndpoint(),
                    de.captaingoldfish.scim.sdk.common.resources.Group.class);
            for (var g : remoteGroups) {
                boolean match = (externalId != null && externalId.equals(g.getId().orElse(null)))
                        || groupAdapter.getDisplayName().equals(g.getDisplayName().orElse(null));
                if (!match) {
                    continue;
                }
                // 🚨 Re-read the group on its own. Databricks does NOT populate `members` in the
                // /Groups LIST response - only in GET /Groups/{id}. Trusting the list makes current
                // membership look permanently empty, which has two consequences:
                //   * removals compute as (currentUsers - desired) = {} and NEVER propagate, silently;
                //   * "preserved" non-Keycloak members are never even seen, so the guard that protects
                //     service principals is not actually exercised - it only appears to work because an
                //     empty current set can produce nothing but additions.
                // Verified on the account SCIM API 2026-08-24: list -> 0 members, get -> 2 members.
                String id = g.getId().orElse(null);
                if (id != null) {
                    var oneResponse = scimRequestBuilder
                            .get(de.captaingoldfish.scim.sdk.common.resources.Group.class,
                                    "/" + adapter.getSCIMEndpoint() + "/" + id, null)
                            .sendRequest();
                    if (oneResponse.isSuccess() && oneResponse.getResource() != null) {
                        groupAdapter.setRemoteMembers(oneResponse.getResource().getMembers());
                    } else {
                        LOGGER.warnf("GET of group %s returned %d; falling back to the list view "
                                + "(additions only)", id, oneResponse.getHttpStatus());
                        groupAdapter.setRemoteMembers(g.getMembers());
                    }
                } else {
                    groupAdapter.setRemoteMembers(g.getMembers());
                }
                break;
            }
        } catch (Exception e) {
            // Without the current membership we cannot compute a safe delta. Leave remoteMembers empty:
            // the delta then contains only additions, which can never remove anyone.
            LOGGER.warnf("Could not read current membership of group %s (%s); "
                    + "the patch will be additions only", groupAdapter.getDisplayName(), e.getMessage());
        }

        groupAdapter.setEmailByRemoteUserId(remoteId -> {
            if (remoteId == null || remoteId.isBlank()) {
                return null;
            }
            try {
                var users = fetchAllResources("/Users",
                        de.captaingoldfish.scim.sdk.common.resources.User.class);
                for (var u : users) {
                    if (remoteId.equals(u.getId().orElse(null))) {
                        var emails = u.getEmails();
                        if (emails != null && !emails.isEmpty() && emails.get(0).getValue().isPresent()) {
                            return emails.get(0).getValue().get();
                        }
                        return u.getUserName().orElse(null);
                    }
                }
            } catch (Exception e) {
                LOGGER.warnf("Remote user lookup by id failed for %s: %s", remoteId, e.getMessage());
            }
            return null;
        });

        groupAdapter.setRemoteUserIdByEmail(email -> {
            if (email == null || email.isBlank()) {
                return null;
            }
            try {
                var users = fetchAllResources("/Users",
                        de.captaingoldfish.scim.sdk.common.resources.User.class);
                for (var u : users) {
                    var emails = u.getEmails();
                    if (emails != null) {
                        for (var e : emails) {
                            if (e.getValue().isPresent() && email.equalsIgnoreCase(e.getValue().get())) {
                                return u.getId().orElse(null);
                            }
                        }
                    }
                    if (email.equalsIgnoreCase(u.getUserName().orElse(""))) {
                        return u.getId().orElse(null);
                    }
                }
            } catch (Exception e) {
                LOGGER.warnf("Remote user lookup by email failed for %s: %s", email, e.getMessage());
            }
            return null;
        });
    }


    /**
     * Applies the SERVER -> KEYCLOAK half of the merge, then records the agreed member set as the new
     * baseline on the Keycloak group.
     *
     * The baseline is what makes the next sync able to tell an addition here from a removal there; if
     * it is not written, every sync degenerates back to "two states, no history" and the plugin starts
     * guessing again.
     */
    private void applyLocalMembership(GroupAdapter adapter) {
        var realm = session.getContext().getRealm();
        var group = session.groups().getGroupById(realm, adapter.getId());
        if (group == null) {
            LOGGER.warnf("Group %s vanished locally during sync; not applying local membership", adapter.getId());
            return;
        }
        // Remote members we could NOT add to the Keycloak group. These must be kept OUT of the
        // baseline - see the comment where the baseline is written below.
        Set<String> unapplied = new HashSet<>();
        for (String remoteId : adapter.getLocalAdditions()) {
            String email = adapter.emailForRemoteId(remoteId);
            var user = email == null ? null : session.users().getUserByEmail(realm, email);
            if (user == null) {
                LOGGER.warnf("Cannot add remote member %s (%s) to group %s locally: no Keycloak user "
                        + "with that email. Excluding it from the baseline so the next sync retries "
                        + "instead of deleting it on the server.", remoteId, email, group.getName());
                unapplied.add(remoteId);
                continue;
            }
            user.joinGroup(group);
            LOGGER.infof("Group %s: added %s locally (changed on the SCIM server)", group.getName(), email);
        }
        for (String remoteId : adapter.getLocalRemovals()) {
            String email = adapter.emailForRemoteId(remoteId);
            var user = email == null ? null : session.users().getUserByEmail(realm, email);
            if (user == null) {
                continue;
            }
            user.leaveGroup(group);
            LOGGER.infof("Group %s: removed %s locally (removed on the SCIM server)", group.getName(), email);
        }
        // 🚨 The baseline must describe what is ACTUALLY true in Keycloak, not what we intended.
        //
        // A member that exists on the server but resolves to no Keycloak user is skipped above. If it
        // were still written into the baseline, the NEXT sync would compute
        // `removedInKeycloak = baseline - desired` and find it there, while `currentUsers` still holds
        // it on the server - producing a PATCH that REMOVES it remotely. A lookup failure would
        // silently become a deletion, one sync later.
        //
        // Excluding it instead makes the next sync see it as `addedOnServer` again and retry the local
        // add. The failure stays a failure until someone creates the Keycloak user; it never escalates
        // into data loss.
        var agreed = new HashSet<>(adapter.getAgreedMembers());
        if (!unapplied.isEmpty()) {
            agreed.removeAll(unapplied);
            LOGGER.warnf("Group %s: %d remote member(s) could not be applied locally and were excluded "
                    + "from the baseline; they will be retried on the next sync.",
                    group.getName(), unapplied.size());
        }
        group.setSingleAttribute(GroupAdapter.BASELINE_ATTR, String.join(",", agreed));
        LOGGER.infof("Group %s: baseline updated to %d member(s)", group.getName(), agreed.size());
    }

    private <M extends RoleMapperModel, S extends ResourceNode, A extends Adapter<M, S>> boolean inScope(A adapter) {
        if (!(adapter instanceof GroupAdapter groupAdapter)) {
            return true;
        }
        String filter = this.model.get("group-filter");
        if (filter == null || filter.trim().isEmpty()) {
            return true;
        }
        String name = groupAdapter.getDisplayName();
        if (name == null) {
            return true;
        }
        for (String p : filter.split(",")) {
            if (Pattern.compile(p.trim()).matcher(name).matches()) {
                return true;
            }
        }
        LOGGER.infof("Skipping group '%s': outside group-filter '%s'", name, filter);
        return false;
    }

    public <M extends RoleMapperModel, S extends ResourceNode, A extends Adapter<M, S>> ServerResponse<S> create(Class<A> aClass,
            M kcModel) {
        var adapter = getAdapter(aClass);
        adapter.apply(kcModel);
        if (adapter.skip) {
            return null;
        }
        if (!inScope(adapter)) {
            return null;
        }
        // The create path serialises members through toSCIM(), which needs the same email resolver the
        // patch path uses. Without this, creating a group whose members have no mapping row is
        // impossible - which is exactly how the first ss-qa rehearsal failed on 'scimtest-beta'.
        primeGroupDelta(adapter);
        // If mapping exist then it was created by import so skip.
        if (adapter.query("findById", adapter.getId()).getResultList().size() != 0) {
            return null;
        }

        // Check if we should map to existing
        boolean shouldMap = false;
        String mapUsersConfig = this.model.get("map-existing-users");
        LOGGER.infof("map-existing-users config value: '%s'", mapUsersConfig);
        if (adapter instanceof UserAdapter && "true".equals(mapUsersConfig)) {
            shouldMap = true;
        } else if (adapter instanceof GroupAdapter && "true".equals(this.model.get("map-existing-groups"))) {
            shouldMap = true;
        }

        if (shouldMap) {
            if (tryMapToExisting(aClass, kcModel)) {
                return null;
            }
        }

        LOGGER.debugf("Creating SCIM resource for %s", adapter.getId());
        var retry = registry.retry("create-" + adapter.getId());

        ServerResponse<S> response = retry.executeSupplier(() -> {
            try {
                return scimRequestBuilder
                .create(adapter.getResourceClass(), ("/" + adapter.getSCIMEndpoint()).formatted())
                .setResource(adapter.toSCIM(false))
                .sendRequest();
            } catch (ResponseException e) {
                throw new RuntimeException(e);
            }
        });

        if (!response.isSuccess()){
            int statusCode = response.getHttpStatus();
            if (shouldMap && statusCode >= 400) {
                LOGGER.infof("Create failed with %d for %s, attempting to map to existing resource", statusCode, adapter.getId());
                tryMapToExisting(aClass, kcModel);
            }
            LOGGER.warn(response.getResponseBody());
            LOGGER.debug(response.getHttpStatus());
        }

        if (response.isSuccess()) {
            adapter.apply(response.getResource());
            adapter.saveMapping();
        }
        return response;
    };

    /**
     * Pushes one local resource to the server.
     *
     * @return true only if the server accepted the change. Previously this returned void and swallowed
     *         every failure into a log line, so callers had no way to tell success from failure -
     *         which is how a sync where every single operation failed still reported
     *         "finished successfully, 2 users updated" on 2026-08-21. Callers MUST branch on this.
     */
    public <M extends RoleMapperModel, S extends ResourceNode, A extends Adapter<M, S>> boolean replace(Class<A> aClass,
            M kcModel) {
        var adapter = getAdapter(aClass);
        try {
            adapter.apply(kcModel);
            if (adapter.skip) {
                // Deliberately not synced (scim-skip attribute) - not a failure.
                return true;
            }
            if (!inScope(adapter)) {
                return true;
            }
            primeGroupDelta(adapter);
            if (adapter instanceof GroupAdapter g) {
                // Build the operations now so we can tell whether anything actually changed. A PATCH with
                // no operations is not a valid SCIM request, and sending one turns a no-op sync into an
                // error.
                try {
                    g.toPatchBuilder(scimRequestBuilder, "/" + adapter.getSCIMEndpoint() + "/" + adapter.getExternalId());
                } catch (IllegalStateException e) {
                    LOGGER.warnf("refusing to sync group %s: %s", g.getDisplayName(), e.getMessage());
                    return false;
                }
                if (!g.hasMembershipChanges()) {
                    // No remote change needed, but the server may still have changed something that has
                    // to come back the other way - and the baseline must be recorded either way.
                    applyLocalMembership(g);
                    LOGGER.infof("Group %s: no remote change needed", g.getDisplayName());
                    return true;
                }
            }
            var resource = adapter.query("findById", adapter.getId()).getSingleResult();
            adapter.apply(resource);
            String url = genScimUrl(adapter.getSCIMEndpoint(), adapter.getExternalId());
            LOGGER.debugf("Replacing SCIM resource for %s at %s", adapter.getId(), url);
            var retry = registry.retry("replace-" + adapter.getId());
            ServerResponse<S> response = retry.executeSupplier(() -> {
                try {
                    LOGGER.debug(adapter.getType());
                    if ((adapter.getType() == "Group" && this.model.get("group-patchOp", false))
                         || (adapter.getType() == "User" && this.model.get("user-patchOp", false))) {
                        return adapter.toPatchBuilder(scimRequestBuilder, url)
                                      .sendRequest();
                    }
                    else {
                        return scimRequestBuilder
                            .update(url, adapter.getResourceClass())
                            .setResource(adapter.toSCIM(false))
                            .sendRequest();
                    }
                } catch (ResponseException e) {
                    throw new RuntimeException(e);
                }
            });
            
            // Handle error responses
            if (!response.isSuccess()) {
                int statusCode = response.getHttpStatus();
                if (statusCode == 405 && adapter.getType().equals("Group") && !this.model.get("group-patchOp", false)) {
                    // PUT not supported for groups, try multiple PATCH operations for Databricks compatibility
                    LOGGER.infof("PUT not supported for groups (405), trying separate PATCH operations for %s", adapter.getId());
                    
                    // For now, just patch members since that's the main issue
                    // TODO: Add support for patching displayName and externalId separately
                    response = adapter.toPatchBuilder(scimRequestBuilder, url).sendRequest();
                    
                    // Check if PATCH also failed with 404/400 (group not found)
                    if (!response.isSuccess()) {
                        int patchStatusCode = response.getHttpStatus();
                        if (patchStatusCode == 404 || patchStatusCode == 400) {
                            // Resource doesn't exist, create it
                            LOGGER.infof("Resource %s not found after PATCH (%d), creating instead", adapter.getId(), patchStatusCode);
                            ServerResponse<S> createResponse = scimRequestBuilder
                                .create(adapter.getResourceClass(), ("/" + adapter.getSCIMEndpoint()).formatted())
                                .setResource(adapter.toSCIM(false))
                                .sendRequest();
                            if (createResponse.isSuccess()) {
                                // Update the existing mapping with the new externalId
                                adapter.apply(createResponse.getResource());
                                var existingMapping = adapter.getMapping();
                                if (existingMapping != null) {
                                    existingMapping.setExternalId(adapter.getExternalId());
                                    getEM().merge(existingMapping);
                                } else {
                                    adapter.saveMapping();
                                }
                                response = createResponse; // Use the successful create response
                            } else {
                                response = createResponse; // Return the failed create response for logging
                            }
                        }
                    }
                } else if (statusCode == 404 || statusCode == 400) {
                    // Resource doesn't exist, create it
                    LOGGER.infof("Resource %s not found (%d), creating instead", adapter.getId(), statusCode);
                    ServerResponse<S> createResponse = scimRequestBuilder
                        .create(adapter.getResourceClass(), ("/" + adapter.getSCIMEndpoint()).formatted())
                        .setResource(adapter.toSCIM(false))
                        .sendRequest();
                    if (createResponse.isSuccess()) {
                        // Update the existing mapping with the new externalId
                        adapter.apply(createResponse.getResource());
                        var existingMapping = adapter.getMapping();
                        if (existingMapping != null) {
                            existingMapping.setExternalId(adapter.getExternalId());
                            getEM().merge(existingMapping);
                        } else {
                            adapter.saveMapping();
                        }
                        response = createResponse; // Use the successful create response
                    } else {
                        response = createResponse; // Return the failed create response for logging
                    }
                }
            }
            
            if (!response.isSuccess()) {
                LOGGER.warnf("replace of %s failed: HTTP %d %s", adapter.getId(), response.getHttpStatus(),
                        response.getResponseBody());
                return false;
            }
            if (adapter instanceof GroupAdapter g2) {
                // Only after the remote half succeeded - otherwise the baseline would claim agreement
                // that does not exist, and the next sync would mistake the un-pushed change for a
                // server-side removal.
                applyLocalMembership(g2);
            }
            return true;
        } catch (NoResultException e) {
            // No local mapping row yet. refreshResources() handles this by calling create(), but the
            // EVENT path calls replace() directly - so without this fallback a group that has never been
            // synced can never be bootstrapped from a membership change, which is what happened to
            // scimtest-alpha/beta on ss-qa. create() maps to an existing remote group by name when
            // map-existing-groups is on, or creates it.
            LOGGER.infof("no scim mapping for %s yet; creating/mapping instead of patching", adapter.getId());
            try {
                var created = this.create(aClass, kcModel);
                return created == null || created.isSuccess() || adapter.getMapping() != null;
            } catch (Exception ce) {
                LOGGER.warnf("create fallback for %s failed: %s", adapter.getId(), ce.getMessage());
                return false;
            }
        } catch (IllegalStateException e) {
            // Raised by GroupAdapter when membership cannot be resolved completely. Refusing is the
            // correct behaviour there, but it is still a failed sync and must be reported as one.
            LOGGER.warnf("refused to replace resource %s: %s", adapter.getId(), e.getMessage());
            return false;
        } catch (Exception e) {
            LOGGER.errorf(e, "replace of %s threw", adapter.getId());
            return false;
        }
    }

    public <M extends RoleMapperModel, S extends ResourceNode, A extends Adapter<M, S>> void delete(Class<A> aClass,
            String id) {
        var adapter = getAdapter(aClass);
        adapter.setId(id);
        LOGGER.debugf("Deleting SCIM resource for %s", id);

        try {
            var resource = adapter.query("findById", adapter.getId()).getSingleResult();
            adapter.apply(resource);

            var retry = registry.retry("delete-" + id);

            ServerResponse<S> response = retry.executeSupplier(() -> {
                try {
                    return scimRequestBuilder.delete(genScimUrl(adapter.getSCIMEndpoint(), adapter.getExternalId()),
                                                                adapter.getResourceClass())
                                             .sendRequest();
                } catch (ResponseException e) {
                    throw new RuntimeException(e);
                }
            });

            if (!response.isSuccess()){
                LOGGER.warn(response.getResponseBody());
                LOGGER.debug(response.getHttpStatus());
            }

            getEM().remove(resource);

        } catch (NoResultException e) {
            LOGGER.warnf("Failed to delete resource %s, scim mapping not found", id);
        }
    }

    public <M extends RoleMapperModel, S extends ResourceNode, A extends Adapter<M, S>> void refreshResources(
            Class<A> aClass,
            SynchronizationResult syncRes) {
        LOGGER.debugf("Refreshing resources for %s", aClass.getSimpleName());
        getAdapter(aClass).getResourceStream().forEach(resource -> {
            try {
                refreshOne(aClass, resource, syncRes);
            } catch (Exception e) {
                // One unsyncable resource must not abort the run. Previously an exception here escaped
                // through ScimDispatcher and ended the sync, so everything after the first bad group was
                // silently never attempted.
                LOGGER.errorf(e, "sync of one %s resource failed", aClass.getSimpleName());
                syncRes.increaseFailed();
            }
        });
    }

    private <M extends RoleMapperModel, S extends ResourceNode, A extends Adapter<M, S>> void refreshOne(
            Class<A> aClass, M resource, SynchronizationResult syncRes) {
        {
            var adapter = getAdapter(aClass);
            adapter.apply(resource);
            String resourceInfo = getResourceInfo(adapter);
            LOGGER.infof("Reconciling local resource %s: %s", adapter.getId(), resourceInfo);
            if (!adapter.skipRefresh()) {
                var mapping = adapter.getMapping();
                if (mapping == null) {
                    LOGGER.infof("Creating remote resource for %s", resourceInfo);
                    ServerResponse<S> createResponse = this.create(aClass, resource);
                    if (createResponse != null && createResponse.isSuccess()) {
                        trackAdded(syncRes, adapter, resourceInfo);
                    } else if (adapter.getMapping() != null) {
                        // Mapped to existing
                        trackMapped(syncRes, adapter, resourceInfo);
                    } else {
                        trackFailed(syncRes, adapter, resourceInfo + " (create failed)");
                    }
                } else {
                    LOGGER.infof("Updating remote resource for %s", resourceInfo);
                    if (this.replace(aClass, resource)) {
                        trackUpdated(syncRes, adapter, resourceInfo);
                    } else {
                        // Was unconditionally trackUpdated, which is what made a wholly failed sync
                        // report success.
                        trackFailed(syncRes, adapter, resourceInfo + " (update failed)");
                    }
                }
            } else {
                LOGGER.infof("Skipping refresh for %s", resourceInfo);
            }
        }
    }

    public <M extends RoleMapperModel, S extends ResourceNode, A extends Adapter<M, S>> void importResources(
            Class<A> aClass, SynchronizationResult syncRes) {
        LOGGER.info("Import");
        LOGGER.debugf("Importing resources for %s", aClass.getSimpleName());
        try {
            var adapter = getAdapter(aClass);
            ServerResponse<ListResponse<S>> response  = scimRequestBuilder.list(scimApplicationBaseUrl + "/" + adapter.getSCIMEndpoint(), adapter.getResourceClass()).get().sendRequest();
            ListResponse<S> resourceTypeListResponse = response.getResource();

            for (var resource : resourceTypeListResponse.getListedResources()) {
                try {
                    LOGGER.infof("Reconciling remote resource %s", resource);
                    adapter = getAdapter(aClass);
                    adapter.apply(resource);

                    String resourceInfo = getResourceInfo(adapter);
                    LOGGER.infof("Processing remote resource: %s", resourceInfo);

                    var mapping = adapter.getMapping();
                    if (mapping != null) {
                        adapter.apply(mapping);
                        if (adapter.entityExists()) {
                            LOGGER.infof("Valid mapping found for %s, skipping", resourceInfo);
                            continue;
                        } else {
                            LOGGER.infof("Deleting dangling mapping for %s", resourceInfo);
                            adapter.deleteMapping();
                        }
                    }

                    var mapped = adapter.tryToMap();
                    if (mapped) {
                        LOGGER.infof("Matched local resource for %s", resourceInfo);
                        adapter.saveMapping();
                    } else {
                        switch (this.model.get("sync-import-action")) {
                            case "CREATE_LOCAL":
                                LOGGER.infof("Creating local resource for %s", resourceInfo);
                                try {
                                    adapter.createEntity();
                                    adapter.saveMapping();
                                    trackAdded(syncRes, adapter, resourceInfo);
                                } catch (Exception e) {
                                    LOGGER.errorf("Failed to create local resource for %s: %s", resourceInfo, e.getMessage());
                                    trackFailed(syncRes, adapter, resourceInfo + " (create failed: " + e.getMessage() + ")");
                                }
                                break;
                            case "DELETE_REMOTE":
                                LOGGER.infof("Deleting remote resource for %s", resourceInfo);
                                try {
                                    scimRequestBuilder
                                        .delete(genScimUrl(adapter.getSCIMEndpoint(),
                                                           resource.getId().get()),
                                                           adapter.getResourceClass())
                                        .sendRequest();
                                    trackRemoved(syncRes, adapter, resourceInfo);
                                } catch (Exception e) {
                                    LOGGER.errorf("Failed to delete remote resource for %s: %s", resourceInfo, e.getMessage());
                                    trackFailed(syncRes, adapter, resourceInfo + " (delete failed: " + e.getMessage() + ")");
                                }
                                break;
                        }
                    }
                } catch (Exception e) {
                    String resourceInfo = adapter != null ? getResourceInfo(adapter) : "unknown";
                    LOGGER.errorf("Failed to process resource %s: %s", resourceInfo, e.getMessage());
                    e.printStackTrace();
                    trackFailed(syncRes, adapter, resourceInfo + " (processing failed: " + e.getMessage() + ")");
                }
            }
        } catch (ResponseException e) {
            throw new RuntimeException(e);
        }
    }

    public <M extends RoleMapperModel, S extends ResourceNode, A extends Adapter<M, S>> void sync(Class<A> aClass,
            SynchronizationResult syncRes) {
        LOGGER.debugf("Starting sync for %s", aClass.getSimpleName());
        if (this.model.get("sync-import", false)) {
            this.importResources(aClass, syncRes);
        }
        if (this.model.get("sync-refresh", false)) {
            this.refreshResources(aClass, syncRes);
        }
        LOGGER.debugf("Sync completed for %s", aClass.getSimpleName());
    }

    public void close() {
        scimRequestBuilder.close();
    }

    private <M extends RoleMapperModel, A extends Adapter<M, ?>> String getResourceInfo(A adapter) {
        if (adapter instanceof UserAdapter userAdapter) {
            String username = userAdapter.getUsername();
            String email = userAdapter.getEmail();
            return String.format("User(username=%s, email=%s)", username, email);
        } else if (adapter instanceof GroupAdapter groupAdapter) {
            String displayName = groupAdapter.getDisplayName();
            return String.format("Group(name=%s, id=%s)", displayName, adapter.getId());
        }
        return String.format("Resource(id=%s)", adapter.getId());
    }

    private <M extends RoleMapperModel, A extends Adapter<M, ?>> void trackAdded(SynchronizationResult syncRes, A adapter, String resourceInfo) {
        if (syncRes instanceof ScimSynchronizationResult scimResult) {
            if (adapter instanceof UserAdapter) {
                scimResult.addAddedUser(resourceInfo);
            } else if (adapter instanceof GroupAdapter) {
                scimResult.addAddedGroup(resourceInfo);
            } else {
                syncRes.increaseAdded();
            }
        } else {
            syncRes.increaseAdded();
        }
    }

    private <M extends RoleMapperModel, A extends Adapter<M, ?>> void trackUpdated(SynchronizationResult syncRes, A adapter, String resourceInfo) {
        if (syncRes instanceof ScimSynchronizationResult scimResult) {
            if (adapter instanceof UserAdapter) {
                scimResult.addUpdatedUser(resourceInfo);
            } else if (adapter instanceof GroupAdapter) {
                scimResult.addUpdatedGroup(resourceInfo);
            } else {
                syncRes.increaseUpdated();
            }
        } else {
            syncRes.increaseUpdated();
        }
    }

    private <M extends RoleMapperModel, A extends Adapter<M, ?>> void trackRemoved(SynchronizationResult syncRes, A adapter, String resourceInfo) {
        if (syncRes instanceof ScimSynchronizationResult scimResult) {
            if (adapter instanceof UserAdapter) {
                scimResult.addRemovedUser(resourceInfo);
            } else if (adapter instanceof GroupAdapter) {
                scimResult.addRemovedGroup(resourceInfo);
            } else {
                syncRes.increaseRemoved();
            }
        } else {
            syncRes.increaseRemoved();
        }
    }

    private <M extends RoleMapperModel, A extends Adapter<M, ?>> void trackMapped(SynchronizationResult syncRes, A adapter, String resourceInfo) {
        if (syncRes instanceof ScimSynchronizationResult scimResult) {
            if (adapter instanceof UserAdapter) {
                scimResult.addMappedUser(resourceInfo);
            } else if (adapter instanceof GroupAdapter) {
                scimResult.addMappedGroup(resourceInfo);
            } else {
                syncRes.increaseUpdated();
            }
        } else {
            syncRes.increaseUpdated();
        }
    }

    private <M extends RoleMapperModel, A extends Adapter<M, ?>> void trackFailed(SynchronizationResult syncRes, A adapter, String resourceInfo) {
        if (syncRes instanceof ScimSynchronizationResult scimResult) {
            if (adapter instanceof UserAdapter) {
                scimResult.addFailedUser(resourceInfo);
            } else if (adapter instanceof GroupAdapter) {
                scimResult.addFailedGroup(resourceInfo);
            } else {
                syncRes.increaseFailed();
            }
        } else {
            syncRes.increaseFailed();
        }
    }

}

package sh.libre.scim.core;

import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.ws.rs.ProcessingException;

import de.captaingoldfish.scim.sdk.client.ScimClientConfig;
import de.captaingoldfish.scim.sdk.client.ScimRequestBuilder;
import de.captaingoldfish.scim.sdk.client.exceptions.IORuntimeException;
import de.captaingoldfish.scim.sdk.client.response.ServerResponse;
import de.captaingoldfish.scim.sdk.common.exceptions.ResponseException;
import de.captaingoldfish.scim.sdk.common.resources.Group;
import de.captaingoldfish.scim.sdk.common.resources.ResourceNode;
import de.captaingoldfish.scim.sdk.common.response.ListResponse;

import org.jboss.logging.Logger;
import org.keycloak.component.ComponentModel;
import org.keycloak.connections.jpa.JpaConnectionProvider;
import org.keycloak.models.GroupModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RoleMapperModel;
import org.keycloak.storage.user.SynchronizationResult;

import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;


public class ScimClient {
    private static final ScimTracingBridge TRACING = ScimTracingBridge.create();
    private static final String GROUP_PATCH_OP_KEY = "group-patchOp";

    final protected Logger LOGGER = Logger.getLogger(ScimClient.class);
    final protected ScimRequestBuilder scimRequestBuilder;
    final protected RetryRegistry registry;
    final protected KeycloakSession session;
    final protected ComponentModel model;
    final protected String scimApplicationBaseUrl;
    final protected ScimAuthHeaders auth;

    public ScimClient(ComponentModel model, KeycloakSession session) {
        this(model, session, new ScimAuthHeaders(model));
    }

    // package-private for tests: inject an explicit token source.
    ScimClient(ComponentModel model, KeycloakSession session, OAuthClientCredentialsTokenSource tokenSource) {
        this(model, session, new ScimAuthHeaders(model, tokenSource));
    }

    private ScimClient(ComponentModel model, KeycloakSession session, ScimAuthHeaders auth) {
        this.model = model;
        this.session = session;
        this.scimApplicationBaseUrl = model.get("endpoint");
        this.auth = auth;

        scimRequestBuilder = new ScimRequestBuilder(scimApplicationBaseUrl, genScimClientConfig());

        RetryConfig retryConfig = RetryConfig.custom()
            .maxAttempts(10)
            .intervalFunction(IntervalFunction.ofExponentialBackoff())
            // Retry on both JAX-RS-level network errors (ProcessingException)
            // and the SCIM SDK's own network-error wrapper (IORuntimeException
            // — what Captain Goldfish throws when Apache HttpClient surfaces
            // SocketException, NoHttpResponseException, etc.). Without
            // IORuntimeException here, the entire retry policy is effectively
            // dead code for this client stack — every real-world transient
            // failure surfaces as IORuntimeException and bypasses retry.
            //
            // HTTP error responses do NOT throw; they return a ServerResponse
            // with isSuccess()=false. The result predicate below retries the
            // transient ones (429 + any 5xx — see isRetryableStatus). See
            // ScimResilienceIT#serverErrorIsRetriedAndEventuallySucceeds.
            .retryExceptions(ProcessingException.class, IORuntimeException.class)
            .retryOnResult(result ->
                result instanceof ServerResponse<?> resp && isRetryableStatus(resp.getHttpStatus()))
            .build();

        registry = RetryRegistry.of(retryConfig);
    }

    protected ScimClientConfig genScimClientConfig() {
        var builder = ScimClientConfig.builder()
        .httpHeaders(auth.headers())
        .connectTimeout(30)
        .requestTimeout(30)
        .socketTimeout(30)
        .expectedHttpResponseHeaders(auth.expectedResponseHeaders())
        // Override the SDK's hardcoded "no TCP connection reuse" + tiny
        // default pool. See KeepAliveConfigManipulator's javadoc for the
        // background — without this, every SCIM call pays full TCP
        // handshake + teardown cost (~43 ms on localhost in our perf
        // measurements).
        .configManipulator(new KeepAliveConfigManipulator());

        if (tlsHostnameVerificationDisabled()) {
            // Operator-opted-out via -Dscim.tls.insecureHostnameVerification=true.
            // Default is strict verification (the SDK / Apache HttpClient
            // falls back to its default verifier when none is set). Use
            // the escape hatch only for dev, internal-CA-with-CN-drift, or
            // explicitly-trusted self-signed scenarios; production should
            // leave it off.
            builder = builder.hostnameVerifier((s, sslSession) -> true);
        }

        return builder.build();
    }

    // package-private for tests
    static boolean tlsHostnameVerificationDisabled() {
        return Boolean.getBoolean("scim.tls.insecureHostnameVerification");
    }

    // 429 (rate-limited) + any 5xx are transient; retry with backoff.
    // 401/403 are deliberately excluded — sendWithAuthRefresh handles those
    // (token re-mint + one retry), and this retry runs inside that wrapper.
    // package-private for tests
    static boolean isRetryableStatus(int status) {
        return status == 429 || status >= 500;
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
            AdapterFactory<M, S, A> factory) {
        return factory.create(session, this.model.getId());
    }

    public <M extends RoleMapperModel, S extends ResourceNode, A extends Adapter<M, S>> void create(
            AdapterFactory<M, S, A> factory, M kcModel) {
        long t0 = System.nanoTime();
        var adapter = getAdapter(factory);
        adapter.apply(kcModel);
        if (adapter.skip) {
            return;
        }
        long t1 = System.nanoTime();
        ScimClientMetrics.APPLY_MODEL_NANOS.add(t1 - t0);
        // If mapping exist then it was created by import so skip.
        if (adapter.query("findById", adapter.getId()).getResultList().size() != 0) {
            return;
        }
        long t2 = System.nanoTime();
        ScimClientMetrics.QUERY_NANOS.add(t2 - t1);
        // Fixed retry name (was "create-" + adapter.getId()). With ScimClient
        // instances now cached across many calls in ScimDispatcher, per-id
        // names would let the RetryRegistry accumulate Retry instances
        // unboundedly. Operation-name granularity is the right scope —
        // resilience4j's per-Retry state isn't per-resource anyway.
        var retry = registry.retry("create");

        try (var span = TRACING.startSpan("scim.create", adapter.getType(), scimApplicationBaseUrl)) {
            ServerResponse<S> response = auth.sendWithAuthRefresh(() -> retry.executeSupplier(() -> {
                try {
                    return scimRequestBuilder
                    .create(adapter.getResourceClass(), ("/" + adapter.getSCIMEndpoint()).formatted())
                    .setResource(adapter.toSCIM(false))
                    .sendRequest();
                } catch (ResponseException e) {
                    throw new RuntimeException(e);
                }
            }));
            long t3 = System.nanoTime();
            ScimClientMetrics.HTTP_NANOS.add(t3 - t2);
            span.setHttpStatus(response.getHttpStatus());
            handleCreateResponse(adapter, response);
        }
    };

    /**
     * Persists the SCIM mapping from a create response, but only when the POST
     * succeeded. A rejected create (e.g. the target returns 400 for a user with
     * no email) carries no parsed resource: {@code response.getResource()} is
     * null, so applying it NPEs and — worse — a mapping persisted here would be
     * a phantom record for a resource the target never created. On failure we
     * log and bail, leaving the user unmapped so a later sync can retry.
     *
     * @return true if the mapping was applied and saved, false if the response
     *         was unsuccessful and skipped.
     */
    // package-private for tests
    <S extends ResourceNode> boolean handleCreateResponse(Adapter<?, S> adapter, ServerResponse<S> response) {
        if (!response.isSuccess()) {
            LOGGER.warnf("Failed to create SCIM resource %s: HTTP %d %s",
                adapter.getId(), response.getHttpStatus(), response.getResponseBody());
            return false;
        }

        long t0 = System.nanoTime();
        adapter.apply(response.getResource());
        long t1 = System.nanoTime();
        ScimClientMetrics.APPLY_RESPONSE_NANOS.add(t1 - t0);
        adapter.saveMapping();
        long t2 = System.nanoTime();
        ScimClientMetrics.SAVE_MAPPING_NANOS.add(t2 - t1);
        ScimClientMetrics.CREATE_COUNT.increment();
        return true;
    }

    public <M extends RoleMapperModel, S extends ResourceNode, A extends Adapter<M, S>> void replace(
            AdapterFactory<M, S, A> factory, M kcModel) {
        var adapter = getAdapter(factory);
        try (var span = TRACING.startSpan("scim.replace", adapter.getType(), scimApplicationBaseUrl)) {
            try {
                adapter.apply(kcModel);
                if (adapter.skip) {
                    return;
                }
                var resource = adapter.query("findById", adapter.getId()).getSingleResult();
                adapter.apply(resource);
                String url = genScimUrl(adapter.getSCIMEndpoint(), adapter.getExternalId());
                var retry = registry.retry("replace");
                ServerResponse<S> response = auth.sendWithAuthRefresh(() -> retry.executeSupplier(() -> {
                    try {
                        LOGGER.info(adapter.getType());
                        if ((adapter.getType() == "Group" && this.model.get(GROUP_PATCH_OP_KEY, false))
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
                }));
                if (!response.isSuccess()) {
                    int statusCode = response.getHttpStatus();
                    if (statusCode == 405 && adapter.getType().equals("Group") && !this.model.get(GROUP_PATCH_OP_KEY, false)) {
                        LOGGER.infof("PUT not supported (405) for group %s, falling back to PATCH", adapter.getId());
                        response = adapter.toPatchBuilder(scimRequestBuilder, url).sendRequest();
                    }
                    if (!response.isSuccess()) {
                        int currentStatus = response.getHttpStatus();
                        if (currentStatus == 404 || currentStatus == 400) {
                            LOGGER.infof("Remote resource %s not found (%d), re-creating", adapter.getId(), currentStatus);
                            ServerResponse<S> createResponse = scimRequestBuilder
                                .create(adapter.getResourceClass(), ("/" + adapter.getSCIMEndpoint()).formatted())
                                .setResource(adapter.toSCIM(false))
                                .sendRequest();
                            if (createResponse.isSuccess()) {
                                adapter.apply(createResponse.getResource());
                                var existingMapping = adapter.getMapping();
                                if (existingMapping != null) {
                                    existingMapping.setExternalId(adapter.getExternalId());
                                    getEM().merge(existingMapping);
                                } else {
                                    adapter.saveMapping();
                                }
                            }
                            response = createResponse;
                        }
                    }
                    if (!response.isSuccess()) {
                        LOGGER.warn(response.getResponseBody());
                        LOGGER.warn(response.getHttpStatus());
                    }
                }
                span.setHttpStatus(response.getHttpStatus());
            } catch (NoResultException e) {
                span.recordError(e);
                LOGGER.warnf("failed to replace resource %s, scim mapping not found", adapter.getId());
            } catch (Exception e) {
                span.recordError(e);
                LOGGER.error(e);
            }
        }
    }

    public <M extends RoleMapperModel, S extends ResourceNode, A extends Adapter<M, S>> void delete(
            AdapterFactory<M, S, A> factory, String id) {
        var adapter = getAdapter(factory);
        adapter.setId(id);

        try (var span = TRACING.startSpan("scim.delete", adapter.getType(), scimApplicationBaseUrl)) {
            try {
                var resource = adapter.query("findById", adapter.getId()).getSingleResult();
                adapter.apply(resource);

                var retry = registry.retry("delete");

                ServerResponse<S> response = auth.sendWithAuthRefresh(() -> retry.executeSupplier(() -> {
                    try {
                        return scimRequestBuilder.delete(genScimUrl(adapter.getSCIMEndpoint(), adapter.getExternalId()),
                                                                    adapter.getResourceClass())
                                                 .sendRequest();
                    } catch (ResponseException e) {
                        throw new RuntimeException(e);
                    }
                }));

                span.setHttpStatus(response.getHttpStatus());
                if (!response.isSuccess()) {
                    LOGGER.warn(response.getResponseBody());
                    LOGGER.warn(response.getHttpStatus());
                }

                getEM().remove(resource);

            } catch (NoResultException e) {
                span.recordError(e);
                LOGGER.warnf("Failed to delete resource %s, scim mapping not found", id);
            }
        }
    }

    /**
     * Propagates a single group-membership change as a minimal SCIM PATCH —
     * one member ADD or REMOVE — rather than re-sending the entire member list
     * via {@link #replace}. Only the {@code GROUP_MEMBERSHIP} event path uses
     * this; group attribute changes still go through {@code replace}.
     *
     * <p>When {@code group-patchOp} is disabled (the remote doesn't support
     * PATCH), this falls back to a full {@code replace} so behaviour is
     * unchanged for those deployments. A missing group or user mapping (e.g.
     * the user was never synced) is logged and skipped, mirroring {@link #delete}.
     */
    public void patchGroupMembership(
            AdapterFactory<GroupModel, Group, GroupAdapter> factory,
            String groupId, String userId, boolean isAdd) {

        if (!this.model.get(GROUP_PATCH_OP_KEY, false)) {
            var group = session.groups().getGroupById(
                    session.getContext().getRealm(), groupId);
            this.replace(factory, group);
            return;
        }

        var adapter = getAdapter(factory);
        try (var span = TRACING.startSpan(
                isAdd ? "scim.group.member.add" : "scim.group.member.remove",
                "Group", scimApplicationBaseUrl)) {
            try {
                adapter.setId(groupId);
                var groupMapping = adapter.query("findById", groupId).getSingleResult();
                adapter.apply(groupMapping);

                var userMapping = adapter.query("findById", userId, "User").getSingleResult();
                String userExternalId = userMapping.getExternalId();
                String url = genScimUrl(adapter.getSCIMEndpoint(), adapter.getExternalId());

                var retry = registry.retry("patchMembership");
                ServerResponse<Group> response = auth.sendWithAuthRefresh(
                    () -> retry.executeSupplier(() -> {
                        try {
                            return adapter.toMembershipPatchBuilder(
                                    scimRequestBuilder, url, userExternalId, isAdd)
                                .sendRequest();
                        } catch (ResponseException e) {
                            throw new RuntimeException(e);
                        }
                    }));

                span.setHttpStatus(response.getHttpStatus());
                if (!response.isSuccess()) {
                    LOGGER.warnf("Failed to PATCH membership for group %s / user %s: %d %s",
                            groupId, userId, response.getHttpStatus(), response.getResponseBody());
                }
            } catch (NoResultException e) {
                span.recordError(e);
                LOGGER.infof("Skipping membership patch: no SCIM mapping for group %s or user %s",
                        groupId, userId);
            }
        }
    }

    /**
     * Ensures a federated user's membership in one group is reflected in SCIM:
     * the SCIM group exists, and the user is a member. Used by the LDAP-import
     * path, which has no membership delta to work from and must re-assert current
     * memberships on every import (additions only — removals are out of scope).
     *
     * <p>Both underlying operations are idempotent, so re-asserting every import
     * is cheap after the first time: {@link #create} short-circuits once the
     * group has a local mapping, and the member-add is a single-member delta
     * PATCH the server already has.
     *
     * <p>When {@code group-patchOp=false}, {@link #patchGroupMembership} falls
     * back to a full {@code replace} that itself provisions the group and the
     * membership, so the explicit ensure-group {@link #create} is redundant and
     * skipped. A missing local group is logged and skipped.
     */
    public void ensureGroupMembership(
            AdapterFactory<GroupModel, Group, GroupAdapter> factory,
            String groupId, String userId) {
        var group = session.groups().getGroupById(session.getContext().getRealm(), groupId);
        if (group == null) {
            LOGGER.infof("Skipping membership ensure: group %s not found locally", groupId);
            return;
        }
        if (this.model.get(GROUP_PATCH_OP_KEY, false)) {
            this.create(factory, group);
        }
        this.patchGroupMembership(factory, groupId, userId, true);
    }

    public <M extends RoleMapperModel, S extends ResourceNode, A extends Adapter<M, S>> void refreshResources(
            AdapterFactory<M, S, A> factory,
            SynchronizationResult syncRes) {
        LOGGER.info("Refresh resources");
        try (var ignored = TRACING.startSpan("scim.sync.refresh", getAdapter(factory).getType(), scimApplicationBaseUrl)) {
            getAdapter(factory).getResourceStream().forEach(resource -> {
                var adapter = getAdapter(factory);
                adapter.apply(resource);
                LOGGER.infof("Reconciling local resource %s", adapter.getId());
                if (!adapter.skipRefresh()) {
                    var mapping = adapter.getMapping();
                    if (mapping == null) {
                        LOGGER.info("Creating it");
                        this.create(factory, resource);
                    } else {
                        LOGGER.info("Replacing it");
                        this.replace(factory, resource);
                    }
                    syncRes.increaseUpdated();
                }
            });
        }
    }

    public <M extends RoleMapperModel, S extends ResourceNode, A extends Adapter<M, S>> void importResources(
            AdapterFactory<M, S, A> factory, SynchronizationResult syncRes) {
        LOGGER.info("Import");
        try (var ignored = TRACING.startSpan("scim.sync.import", getAdapter(factory).getType(), scimApplicationBaseUrl)) {
            try {
                var adapter = getAdapter(factory);
                String listUrl = scimApplicationBaseUrl + "/" + adapter.getSCIMEndpoint();
                Class<S> resourceClass = adapter.getResourceClass();
                ServerResponse<ListResponse<S>> response = auth.sendWithAuthRefresh(() ->
                    scimRequestBuilder.list(listUrl, resourceClass).get().sendRequest());
                ListResponse<S> resourceTypeListResponse = response.getResource();

                for (var resource : resourceTypeListResponse.getListedResources()) {
                    try {
                        LOGGER.infof("Reconciling remote resource %s", resource);
                        adapter = getAdapter(factory);
                        adapter.apply(resource);

                        var mapping = adapter.getMapping();
                        if (mapping != null) {
                            adapter.apply(mapping);
                            if (adapter.entityExists()) {
                                LOGGER.info("Valid mapping found, skipping");
                                continue;
                            } else {
                                LOGGER.info("Delete a dangling mapping");
                                adapter.deleteMapping();
                            }
                        }

                        var mapped = adapter.tryToMap();
                        if (mapped) {
                            LOGGER.info("Matched");
                            adapter.saveMapping();
                        } else {
                            switch (this.model.get("sync-import-action")) {
                                case "CREATE_LOCAL":
                                    LOGGER.info("Create local resource");
                                    try {
                                        adapter.createEntity();
                                        adapter.saveMapping();
                                        syncRes.increaseAdded();
                                    } catch (Exception e) {
                                        LOGGER.error(e);
                                    }
                                    break;
                                case "DELETE_REMOTE":
                                    LOGGER.info("Delete remote resource");
                                    scimRequestBuilder
                                        .delete(genScimUrl(adapter.getSCIMEndpoint(),
                                                           resource.getId().get()),
                                                           adapter.getResourceClass())
                                        .sendRequest();
                                    syncRes.increaseRemoved();
                                    break;
                            }
                        }
                    } catch (Exception e) {
                        LOGGER.error(e);
                        e.printStackTrace();
                        syncRes.increaseFailed();
                    }
                }
            } catch (ResponseException e) {
                throw new RuntimeException(e);
            }
        }
    }

    public <M extends RoleMapperModel, S extends ResourceNode, A extends Adapter<M, S>> void sync(
            AdapterFactory<M, S, A> factory, SynchronizationResult syncRes) {
        if (this.model.get("sync-import", false)) {
            this.importResources(factory, syncRes);
        }
        if (this.model.get("sync-refresh", false)) {
            this.refreshResources(factory, syncRes);
        }
    }

    public void close() {
        scimRequestBuilder.close();
    }
}

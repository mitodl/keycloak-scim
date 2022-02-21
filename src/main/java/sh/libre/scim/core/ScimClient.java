package sh.libre.scim.core;

import javax.persistence.EntityManager;
import javax.persistence.NoResultException;
import javax.ws.rs.client.Client;

import com.unboundid.scim2.client.ScimService;
import com.unboundid.scim2.common.ScimResource;
import com.unboundid.scim2.common.exceptions.ScimException;
import com.unboundid.scim2.common.types.UserResource;

import org.jboss.logging.Logger;
import org.jboss.resteasy.client.jaxrs.ResteasyClientBuilder;
import org.keycloak.component.ComponentModel;
import org.keycloak.connections.jpa.JpaConnectionProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RoleMapperModel;
import org.keycloak.storage.user.SynchronizationResult;

import io.github.resilience4j.core.IntervalFunction;
import io.github.resilience4j.retry.RetryConfig;
import io.github.resilience4j.retry.RetryRegistry;

public class ScimClient {
    final protected Logger LOGGER = Logger.getLogger(ScimClient.class);
    final protected Client client = ResteasyClientBuilder.newClient();
    final protected ScimService scimService;
    final protected RetryRegistry registry;
    final protected KeycloakSession session;
    final protected String contentType;
    final protected ComponentModel model;

    public ScimClient(ComponentModel model, KeycloakSession session) {
        this.model = model;
        this.contentType = model.get("content-type");

        this.session = session;
        var target = client.target(model.get("endpoint"));
        if (model.get("auth-mode").equals("BEARER")) {
            target = target.register(new BearerAuthentication(model.get("auth-bearer-token")));
        }

        scimService = new ScimService(target);

        RetryConfig retryConfig = RetryConfig.custom()
                .maxAttempts(10)
                .intervalFunction(IntervalFunction.ofExponentialBackoff())
                .build();
        registry = RetryRegistry.of(retryConfig);
    }

    protected EntityManager getEM() {
        return session.getProvider(JpaConnectionProvider.class).getEntityManager();
    }

    protected String getRealmId() {
        return session.getContext().getRealm().getId();
    }

    protected <M extends RoleMapperModel, S extends ScimResource, A extends Adapter<M, S>> A getAdapter(
            Class<A> aClass) {
        try {
            return aClass.getDeclaredConstructor(String.class, String.class, EntityManager.class)
                    .newInstance(getRealmId(), this.model.getId(), getEM());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public <M extends RoleMapperModel, S extends ScimResource, A extends Adapter<M, S>> void create(Class<A> aClass,
            M kcModel) {
        var adapter = getAdapter(aClass);
        adapter.apply(kcModel);
        var retry = registry.retry("create-" + adapter.getId());
        var spUser = retry.executeSupplier(() -> {
            try {
                return scimService.createRequest(adapter.getSCIMEndpoint(), adapter.toSCIM(false))
                        .contentType(contentType).invoke();
            } catch (ScimException e) {
                throw new RuntimeException(e);
            }
        });
        adapter.apply(spUser);
        adapter.saveMapping();
    };

    public <M extends RoleMapperModel, S extends ScimResource, A extends Adapter<M, S>> void replace(Class<A> aClass,
            M kcModel) {
        var adapter = getAdapter(aClass);
        try {
            adapter.apply(kcModel);
            var resource = adapter.query("findById", adapter.getId()).getSingleResult();
            adapter.apply(resource);
            var retry = registry.retry("replace-" + adapter.getId());
            retry.executeSupplier(() -> {
                try {
                    return scimService.replaceRequest(adapter.toSCIM(true)).contentType(contentType).invoke();
                } catch (ScimException e) {
                    throw new RuntimeException(e);
                }
            });
        } catch (NoResultException e) {
            LOGGER.warnf("failed to replace resource %s, scim mapping not found", adapter.getId());
        } catch (Exception e) {
            LOGGER.error(e);
        }
    }

    public <M extends RoleMapperModel, S extends ScimResource, A extends Adapter<M, S>> void delete(Class<A> aClass,
            String id) {
        var adapter = getAdapter(aClass);
        adapter.setId(id);
        try {
            var resource = adapter.query("findById", adapter.getId()).getSingleResult();
            adapter.apply(resource);
            var retry = registry.retry("delete-" + id);
            retry.executeSupplier(() -> {
                try {
                    scimService.deleteRequest(adapter.getSCIMEndpoint(), resource.getExternalId())
                            .contentType(contentType).invoke();
                } catch (ScimException e) {
                    throw new RuntimeException(e);
                }
                return "";
            });
            getEM().remove(resource);
        } catch (NoResultException e) {
            LOGGER.warnf("Failed to delete resource %s, scim mapping not found", id);
        }
    }

    public void refreshUsers(SynchronizationResult syncRes) {
        LOGGER.info("Refresh Users");
        this.session.users().getUsersStream(this.session.getContext().getRealm()).forEach(kcUser -> {
            LOGGER.infof("Reconciling local user %s", kcUser.getId());
            if (!kcUser.getUsername().equals("admin")) {
                var adapter = getAdapter(UserAdapter.class);
                adapter.apply(kcUser);
                var mapping = adapter.getMapping();
                if (mapping == null) {
                    LOGGER.info("Creating it");
                    this.create(UserAdapter.class, kcUser);
                } else {
                    LOGGER.info("Replacing it");
                    this.replace(UserAdapter.class, kcUser);
                }
                syncRes.increaseUpdated();
            }
        });
    }

    public void importUsers(SynchronizationResult syncRes) {
        LOGGER.info("Import Users");
        try {
            var spUsers = scimService.searchRequest("Users").contentType(contentType).invoke(UserResource.class);
            for (var spUser : spUsers) {
                try {
                    LOGGER.infof("Reconciling remote user %s", spUser.getId());
                    var adapter = getAdapter(UserAdapter.class);
                    adapter.apply(spUser);

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
                        LOGGER.info("Matched a user");
                        adapter.saveMapping();
                    } else {
                        switch (this.model.get("sync-import-action")) {
                            case "CREATE_LOCAL":
                                LOGGER.info("Create local user");
                                adapter.createEntity();
                                adapter.saveMapping();
                                syncRes.increaseAdded();
                                break;
                            case "DELETE_REMOTE":
                                LOGGER.info("Delete remote user");
                                scimService.deleteRequest("Users", spUser.getId()).contentType(contentType)
                                        .invoke();
                                syncRes.increaseRemoved();
                                break;
                        }
                    }
                } catch (Exception e) {
                    syncRes.increaseFailed();
                }
            }
        } catch (ScimException e) {
            throw new RuntimeException(e);
        }
    }

    public void sync(SynchronizationResult syncRes) {
        if (this.model.get("sync-import", false)) {
            this.importUsers(syncRes);
        }
        if (this.model.get("sync-refresh", false)) {
            this.refreshUsers(syncRes);
        }
    }

    public void close() {
        client.close();
    }
}

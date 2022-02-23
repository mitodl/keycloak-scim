package sh.libre.scim.core;

import javax.persistence.EntityManager;
import javax.persistence.NoResultException;
import javax.ws.rs.client.Client;

import com.unboundid.scim2.client.ScimService;
import com.unboundid.scim2.common.ScimResource;
import com.unboundid.scim2.common.exceptions.ScimException;

import org.jboss.logging.Logger;
import org.jboss.resteasy.client.jaxrs.ResteasyClientBuilder;
import org.keycloak.component.ComponentModel;
import org.keycloak.connections.jpa.JpaConnectionProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RoleMapperModel;
import org.keycloak.storage.user.SynchronizationResult;

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
            return aClass.getDeclaredConstructor(KeycloakSession.class, String.class)
                    .newInstance(session, this.model.getId());
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    public <M extends RoleMapperModel, S extends ScimResource, A extends Adapter<M, S>> void create(Class<A> aClass,
            M kcModel) {
        var adapter = getAdapter(aClass);
        adapter.apply(kcModel);
        var retry = registry.retry("create-" + adapter.getId());
        var resource = retry.executeSupplier(() -> {
            try {
                return scimService.createRequest(adapter.getSCIMEndpoint(),
                        adapter.toSCIM(false))
                        .contentType(contentType).invoke();
            } catch (ScimException e) {
                throw new RuntimeException(e);
            }
        });
        adapter.apply(resource);
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

    public <M extends RoleMapperModel, S extends ScimResource, A extends Adapter<M, S>> void refreshResources(
            Class<A> aClass,
            SynchronizationResult syncRes) {
        LOGGER.info("Refresh resources");
        getAdapter(aClass).getResourceStream().forEach(resource -> {
            var adapter = getAdapter(aClass);
            adapter.apply(resource);
            LOGGER.infof("Reconciling local resource %s", adapter.getId());
            if (!adapter.skipRefresh()) {
                var mapping = adapter.getMapping();
                if (mapping == null) {
                    LOGGER.info("Creating it");
                    this.create(aClass, resource);
                } else {
                    LOGGER.info("Replacing it");
                    this.replace(aClass, resource);
                }
                syncRes.increaseUpdated();
            }
        });

    }

    public <M extends RoleMapperModel, S extends ScimResource, A extends Adapter<M, S>> void importResources(
            Class<A> aClass, SynchronizationResult syncRes) {
        LOGGER.info("Import");
        try {
            var adapter = getAdapter(aClass);
            var resources = scimService.searchRequest(adapter.getSCIMEndpoint()).contentType(contentType)
                    .invoke(adapter.getResourceClass());
            for (var resource : resources) {
                try {
                    LOGGER.infof("Reconciling remote resource %s", resource.getId());
                    adapter = getAdapter(aClass);
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
                                adapter.createEntity();
                                adapter.saveMapping();
                                syncRes.increaseAdded();
                                break;
                            case "DELETE_REMOTE":
                                LOGGER.info("Delete remote resource");
                                scimService.deleteRequest(adapter.getSCIMEndpoint(), resource.getId())
                                        .contentType(contentType)
                                        .invoke();
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
        } catch (ScimException e) {
            throw new RuntimeException(e);
        }
    }

    public <M extends RoleMapperModel, S extends ScimResource, A extends Adapter<M, S>> void sync(Class<A> aClass,
            SynchronizationResult syncRes) {
        if (this.model.get("sync-import", false)) {
            this.importResources(aClass, syncRes);
        }
        if (this.model.get("sync-refresh", false)) {
            this.refreshResources(aClass, syncRes);
        }
    }

    public void close() {
        client.close();
    }
}

package sh.libre.scim.core;

import java.util.stream.Stream;

import jakarta.persistence.EntityManager;
import jakarta.persistence.NoResultException;
import jakarta.persistence.TypedQuery;
import jakarta.ws.rs.NotFoundException;

import org.jboss.logging.Logger;
import org.keycloak.connections.jpa.JpaConnectionProvider;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.RealmModel;
import org.keycloak.models.RoleMapperModel;
import org.keycloak.common.util.MultivaluedHashMap;
import sh.libre.scim.jpa.ScimResource;
import de.captaingoldfish.scim.sdk.common.resources.ResourceNode;
import de.captaingoldfish.scim.sdk.client.ScimRequestBuilder;
import de.captaingoldfish.scim.sdk.client.builder.PatchBuilder;
import java.util.regex.Pattern;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.HashSet;
import java.util.stream.Stream;
import java.util.regex.Pattern;
import java.util.List;
import java.util.ArrayList;
import java.util.Set;
import java.util.HashSet;

public abstract class Adapter<M extends RoleMapperModel, S extends ResourceNode> {

    protected final Logger LOGGER;
    protected final String realmId;
    protected final RealmModel realm;
    protected final String type;
    protected final String componentId;
    protected final EntityManager em;
    protected final KeycloakSession session;

    protected String id;
    protected String externalId;
    protected Boolean skip = false;

    public Adapter(KeycloakSession session, String componentId, String type, Logger logger) {
        this.session = session;
        this.realm = session.getContext().getRealm();
        this.realmId = session.getContext().getRealm().getId();
        this.componentId = componentId;
        this.em = session.getProvider(JpaConnectionProvider.class).getEntityManager();
        this.type = type;
        this.LOGGER = logger;
    }

    public String getType() {
        return type;
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        if (this.id == null) {
            this.id = id;
        }
    }

    public String getExternalId() {
        return externalId;
    }

    public void setExternalId(String externalId) {
        if (this.externalId == null) {
            this.externalId = externalId;
        }
    }

    public String getSCIMEndpoint() {
        return type + "s";
    }

    public ScimResource toMapping() {
        var entity = new ScimResource();
        entity.setType(type);
        entity.setId(id);
        entity.setExternalId(externalId);
        entity.setComponentId(componentId);
        entity.setRealmId(realmId);
        return entity;
    }

    public TypedQuery<ScimResource> query(String query, String id) {
        return query(query, id, type);
    }

    public TypedQuery<ScimResource> query(String query, String id, String type) {
        return this.em
                .createNamedQuery(query, ScimResource.class)
                .setParameter("type", type)
                .setParameter("realmId", realmId)
                .setParameter("componentId", componentId)
                .setParameter("id", id);
    }

    public ScimResource getMapping() {
        try {
            if (this.id != null) {
                return this.query("findById", id).getSingleResult();
            }
            if (this.externalId != null) {
                return this.query("findByExternalId", externalId).getSingleResult();
            }
        } catch (NotFoundException e) {
        } catch (NoResultException e) {
        } catch (Exception e) {
            LOGGER.error(e);
        }

        return null;
    }

    public void saveMapping() {
        this.em.persist(toMapping());
    }

    public void deleteMapping() {
        var mapping = this.em.merge(toMapping());
        this.em.remove(mapping);
    }

    public void apply(ScimResource mapping) {
        setId(mapping.getId());
        setExternalId(mapping.getExternalId());
    }

    public abstract void apply(M model);

    public abstract void apply(S resource);

    public abstract Class<S> getResourceClass();

    public abstract S toSCIM(Boolean addMeta);

    public abstract PatchBuilder<S> toPatchBuilder(ScimRequestBuilder scimRequestBuilder, String url);

    public abstract Boolean entityExists();

    public abstract Boolean tryToMap();

    public abstract void createEntity() throws Exception;

    public abstract Stream<M> getResourceStream();

    protected Stream<org.keycloak.models.GroupModel> getFilteredGroups() {
        var model = getModel();
        if (model == null) {
            return this.session.groups().getGroupsStream(this.session.getContext().getRealm());
        }
        var filterList = model.get("group-filter");
        String filter = filterList != null && !filterList.isEmpty() ? filterList.get(0) : null;
        if (filter == null || filter.trim().isEmpty()) {
            return this.session.groups().getGroupsStream(this.session.getContext().getRealm());
        }
        var patternStrings = filter.split(",");
        List<Pattern> patterns = new ArrayList<>();
        for (var p : patternStrings) {
            patterns.add(Pattern.compile(p.trim()));
        }
        Set<org.keycloak.models.GroupModel> filteredGroups = new HashSet<>();
        this.session.groups().getGroupsStream(this.session.getContext().getRealm())
            .filter(g -> patterns.stream().anyMatch(p -> p.matcher(g.getName()).matches()))
            .forEach(g -> {
                addGroupRecursively(filteredGroups, g);
            });
        return filteredGroups.stream();
    }

    public Boolean skipRefresh() {
        return skip;
    }

    private void addGroupRecursively(Set<org.keycloak.models.GroupModel> groups, org.keycloak.models.GroupModel group) {
        if (groups.add(group)) {
            group.getSubGroupsStream().forEach(sub -> addGroupRecursively(groups, sub));
        }
    }

    protected MultivaluedHashMap<String, String> getModel() {
        var component = this.session.getContext().getRealm().getComponent(this.componentId);
        if (component != null) {
            return component.getConfig();
        }
        return null;
    }

}

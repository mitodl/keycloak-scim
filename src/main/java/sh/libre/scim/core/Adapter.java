package sh.libre.scim.core;

import javax.persistence.EntityManager;
import javax.persistence.TypedQuery;
import javax.ws.rs.NotFoundException;

import org.jboss.logging.Logger;
import org.keycloak.models.RoleMapperModel;

import sh.libre.scim.jpa.ScimResource;

public abstract class Adapter<M extends RoleMapperModel, S extends com.unboundid.scim2.common.ScimResource> {

    protected final Logger LOGGER;
    protected final String realmId;
    protected final String type;
    protected final String componentId;
    protected final EntityManager em;

    protected String id;
    protected String externalId;

    public Adapter(String realmId, String componentId, EntityManager em, String type, Logger logger) {
        this.realmId = realmId;
        this.componentId = componentId;
        this.em = em;
        this.type = type;
        this.LOGGER = logger;
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
        } catch (Exception e) {
            LOGGER.error(e);
        }

        return null;
    }

    public void saveMapping() {
        this.em.persist(toMapping());
    }

    public void deleteMapping() {
        this.em.remove(this.toMapping());
    }

    public abstract void apply(M model);

    public abstract void apply(S resource);

    public abstract void apply(ScimResource resource);

    public abstract S toSCIM(Boolean addMeta);

    public abstract Boolean entityExists();

    public abstract Boolean tryToMap();

}

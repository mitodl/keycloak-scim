package sh.libre.scim.jpa;

import java.io.Serializable;
import java.util.Objects;

public class ScimResourceId implements Serializable {
    private String id;
    private String realmId;
    private String componentId;
    private String type;
    private String externalId;

    public ScimResourceId() {
    }

    public ScimResourceId(String id, String realmId, String componentId, String type, String externalId) {
        this.setId(id);
        this.setRealmId(realmId);
        this.setComponentId(componentId);
        this.setType(type);
        this.setExternalId(externalId);
    }

    public String getId() {
        return id;
    }

    public void setId(String id) {
        this.id = id;
    }

    public String getRealmId() {
        return realmId;
    }

    public void setRealmId(String realmId) {
        this.realmId = realmId;
    }

    public String getComponentId() {
        return componentId;
    }

    public void setComponentId(String componentId) {
        this.componentId = componentId;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getExternalId() {
        return externalId;
    }

    public void setExternalId(String externalId) {
        this.externalId = externalId;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof ScimResourceId)) {
            return false;
        }
        var o = (ScimResourceId) other;
        return o.id == id
                && o.realmId == realmId
                && o.componentId == componentId
                && o.type == type
                && o.externalId == externalId;
    }

    @Override
    public int hashCode() {
        return Objects.hash(realmId, componentId, type, id, externalId);
    }
}

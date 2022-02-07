package sh.libre.scim.jpa;

import java.io.Serializable;
import java.util.Objects;

public class ScimResourceId implements Serializable {
    private String realmId;
    private String serviceProvider;
    private String type;
    private String remoteId;

    public ScimResourceId() {
    }

    public ScimResourceId(String realmId, String serviceProvider, String type, String remoteId) {
        this.realmId = realmId;
        this.serviceProvider = serviceProvider;
        this.type = type;
        this.remoteId = remoteId;
    }

    public String getRealmId() {
        return realmId;
    }

    public void setRealmId(String realmId) {
        this.realmId = realmId;
    }

    public String getServiceProvider() {
        return serviceProvider;
    }

    public void setServiceProvider(String serviceProvider) {
        this.serviceProvider = serviceProvider;
    }

    public String getType() {
        return type;
    }

    public void setType(String type) {
        this.type = type;
    }

    public String getRemoteId() {
        return realmId;
    }

    public void setRemoteId(String remoteId) {
        this.remoteId = remoteId;
    }

    @Override
    public boolean equals(Object other) {
        if (this == other)
            return true;
        if (!(other instanceof ScimResourceId))
            return false;
        var o = (ScimResourceId) other;
        return (o.realmId == realmId &&
                o.serviceProvider == serviceProvider &&
                o.type == type &&
                o.remoteId == remoteId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(realmId, serviceProvider, type, remoteId);
    }
}

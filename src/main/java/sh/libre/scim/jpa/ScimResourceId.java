package sh.libre.scim.jpa;

import java.io.Serializable;
import java.util.Objects;

public class ScimResourceId implements Serializable {
    private String realm;
    private String serviceProvider;
    private String type;
    private String remoteId;

    public ScimResourceId() {
    }

    public ScimResourceId(String realm, String serviceProvider, String type, String remoteId) {
        this.realm = realm;
        this.serviceProvider = serviceProvider;
        this.type = type;
        this.remoteId = remoteId;
    }

    public String getRealm() {
        return realm;
    }

    public void setRealm(String realm) {
        this.realm = realm;
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
        return remoteId;
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
        return (o.realm == realm &&
                o.serviceProvider == serviceProvider &&
                o.type == type &&
                o.remoteId == remoteId);
    }

    @Override
    public int hashCode() {
        return Objects.hash(realm, serviceProvider, type, remoteId);
    }
}

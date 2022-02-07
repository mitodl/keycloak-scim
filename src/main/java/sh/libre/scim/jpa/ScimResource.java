package sh.libre.scim.jpa;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.IdClass;
import javax.persistence.NamedQuery;
import javax.persistence.NamedQueries;
import javax.persistence.Table;

@Entity
@IdClass(ScimResourceId.class)
@Table(name = "SCIM_RESOURCE")
@NamedQueries({
                @NamedQuery(name = "findByLocalId", query = "from ScimResource where realmId = :realmId and type = :type and serviceProvider = :serviceProvider and localId = :id"),
                @NamedQuery(name = "findByRemoteId", query = "from ScimResource where realmId = :realmId and type = :type and serviceProvider = :serviceProvider and remoteId = :id") })
public class ScimResource {

        @Id
        @Column(name = "REALM_ID", nullable = false)
        private String realmId;

        @Id
        @Column(name = "SERVICE_PROVIDER", nullable = false)
        private String serviceProvider;

        @Id
        @Column(name = "TYPE", nullable = false)
        private String type;

        @Id
        @Column(name = "REMOTE_ID", nullable = false)
        private String remoteId;

        @Column(name = "LOCAL_ID", nullable = false)
        private String localId;

        // public ScimResource() {
        // }

        // public ScimResource(String realmId, String serviceProvider, String type, String remoteId, String localId) {
        //         this.realmId = realmId;
        //         this.serviceProvider = serviceProvider;
        //         this.type = type;
        //         this.remoteId = remoteId;
        //         this.localId = localId;
        // }

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
                return remoteId;
        }

        public void setRemoteId(String remoteId) {
                this.remoteId = remoteId;
        }

        public String getLocalId() {
                return localId;
        }

        public void setLocalId(String localId) {
                this.localId = localId;
        }
}

package sh.libre.scim.jpa;

import javax.persistence.Column;
import javax.persistence.Entity;
import javax.persistence.Id;
import javax.persistence.IdClass;
import javax.persistence.JoinColumn;
import javax.persistence.ManyToOne;
import javax.persistence.NamedQuery;
import javax.persistence.NamedQueries;
import javax.persistence.Table;

import org.keycloak.models.jpa.entities.ComponentEntity;
import org.keycloak.models.jpa.entities.RealmEntity;

@Entity
@IdClass(ScimResourceId.class)
@Table(name = "SCIM_RESOURCE")
@NamedQueries({
                @NamedQuery(name = "findByLocalId", query = "from ScimResource where realm = :realm and type = :type and serviceProvider = :serviceProvider and localId = :id"),
                @NamedQuery(name = "findByRemoteId", query = "from ScimResource where realm = :realm and type = :type and serviceProvider = :serviceProvider and remoteId = :id") })
public class ScimResource {
        @Id
        @ManyToOne
        @JoinColumn(name = "REALM_ID", referencedColumnName = "ID")
        private RealmEntity realm;

        @Id
        @ManyToOne
        @JoinColumn(name = "SERVICE_PROVIDER", referencedColumnName = "ID")
        private ComponentEntity serviceProvider;
        
        @Id
        @Column(name = "TYPE", nullable = false)
        private String type;

        @Id
        @Column(name = "LOCAL_ID", nullable = false)
        private String localId;

        @Column(name = "REMOTE_ID", nullable = false)
        private String remoteId;

        public RealmEntity getRealm() {
                return realm;
        }

        public void setRealm(RealmEntity realm) {
                this.realm = realm;
        }

        public ComponentEntity getServiceProvider() {
                return serviceProvider;
        }

        public void setServiceProvider(ComponentEntity serviceProvider) {
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

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
                @NamedQuery(name = "findById", query = "from ScimResource where realmId = :realmId and componentId = :componentId and type = :type and id = :id"),
                @NamedQuery(name = "findByExternalId", query = "from ScimResource where realmId = :realmId and componentId = :componentId and type = :type and externalId = :id") })
public class ScimResource {
        @Id
        @Column(name = "ID", nullable = false)
        private String id;

        @Id
        @Column(name = "REALM_ID", nullable = false)
        private String realmId;

        @Id
        @Column(name = "COMPONENT_ID", nullable = false)
        private String componentId;

        @Id
        @Column(name = "TYPE", nullable = false)
        private String type;

        @Id
        @Column(name = "EXTERNAL_ID", nullable = false)
        private String externalId;

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

        public String getExternalId() {
                return externalId;
        }

        public void setExternalId(String externalId) {
                this.externalId = externalId;
        }

        public String getType() {
                return type;
        }

        public void setType(String type) {
                this.type = type;
        }

}

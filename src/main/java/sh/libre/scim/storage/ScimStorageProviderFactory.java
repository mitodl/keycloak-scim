package sh.libre.scim.storage;

import java.util.List;

import javax.ws.rs.core.MediaType;

import com.unboundid.scim2.client.ScimService;

import org.keycloak.component.ComponentModel;
import org.keycloak.models.KeycloakSession;
import org.keycloak.provider.ProviderConfigProperty;
import org.keycloak.provider.ProviderConfigurationBuilder;
import org.keycloak.storage.UserStorageProviderFactory;

public class ScimStorageProviderFactory implements UserStorageProviderFactory<ScimStorageProvider> {
    public final static String ID = "scim";
    protected static final List<ProviderConfigProperty> configMetadata;
    static {
        configMetadata = ProviderConfigurationBuilder.create()
                .property()
                .name("endpoint")
                .type(ProviderConfigProperty.STRING_TYPE)
                .label("SCIM 2.0 endpoint")
                .helpText("External SCIM 2.0 base " +
                        "URL (/ServiceProviderConfig  /Schemas and /ResourcesTypes should be accessible)")
                .add()
                .property()
                .name("content-type")
                .type(ProviderConfigProperty.LIST_TYPE)
                .label("Endpoint content type")
                .helpText("Only used when endpoint doesn't support application/scim+json")
                .options(MediaType.APPLICATION_JSON.toString(), ScimService.MEDIA_TYPE_SCIM_TYPE.toString())
                .defaultValue(ScimService.MEDIA_TYPE_SCIM_TYPE.toString())
                .add()
                .build();
    }

    @Override
    public ScimStorageProvider create(KeycloakSession session, ComponentModel model) {
        return new ScimStorageProvider();
    }

    @Override
    public String getId() {
        return ID;
    }

    @Override
    public List<ProviderConfigProperty> getConfigProperties() {
        return configMetadata;
    }

}

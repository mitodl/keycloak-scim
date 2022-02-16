package sh.libre.scim.core;

import java.io.IOException;

import javax.ws.rs.client.ClientRequestContext;
import javax.ws.rs.client.ClientRequestFilter;

public class BearerAuthentication implements ClientRequestFilter {
    private final String token;

    BearerAuthentication(String token) {
        this.token = token;
    }

    @Override
    public void filter(ClientRequestContext requestContext) throws IOException {
        requestContext.getHeaders().add("Authorization", "Bearer " + this.token);

    }
}

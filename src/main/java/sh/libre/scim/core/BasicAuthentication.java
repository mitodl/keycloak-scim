package sh.libre.scim.core;

import java.io.IOException;
import java.util.Base64;

import javax.ws.rs.client.ClientRequestContext;
import javax.ws.rs.client.ClientRequestFilter;

public class BasicAuthentication implements ClientRequestFilter {
    private final String user;
    private final String password;

    BasicAuthentication(String user, String password) {
        this.user = user;
        this.password = password;
    }

    @Override
    public void filter(ClientRequestContext requestContext) throws IOException {
        var token = Base64.getEncoder().encodeToString((user + ":" + password).getBytes());
        requestContext.getHeaders().add("Authorization", "Basic " + token);
    }
}

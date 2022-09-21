package sh.libre.scim.core;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Stream;

import com.unboundid.scim2.common.types.Email;
import com.unboundid.scim2.common.types.Meta;
import com.unboundid.scim2.common.types.Role;
import com.unboundid.scim2.common.types.UserResource;

import org.jboss.logging.Logger;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.UserModel;

public class UserAdapter extends Adapter<UserModel, UserResource> {

    private String username;
    private String displayName;
    private String email;
    private Boolean active;
    private String[] roles;

    public UserAdapter(KeycloakSession session, String componentId) {
        super(session, componentId, "User", Logger.getLogger(UserAdapter.class));
    }

    public String getUsername() {
        return username;
    }

    public void setUsername(String username) {
        if (this.username == null) {
            this.username = username;
        }
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        if (this.displayName == null) {
            this.displayName = displayName;
        }
    }

    public String getEmail() {
        return email;
    }

    public void setEmail(String email) {
        if (this.email == null) {
            this.email = email;
        }
    }

    public Boolean getActive() {
        return active;
    }

    public void setActive(Boolean active) {
        if (this.active == null) {
            this.active = active;
        }
    }

    public String[] getRoles() {
        return roles;
    }

    public void setRoles(String[] roles) {
        this.roles = roles;
    }

    @Override
    public Class<UserResource> getResourceClass() {
        return UserResource.class;
    }

    @Override
    public void apply(UserModel user) {
        setId(user.getId());
        setUsername(user.getUsername());
        if (user.getFirstName() != null && user.getLastName() != null) {
            setDisplayName(String.format("%s %s", user.getFirstName(), user.getLastName()));
        } else if (user.getFirstName() != null) {
            setDisplayName(user.getFirstName());
        } else if (user.getLastName() != null) {
            setDisplayName(user.getLastName());
        }
        setEmail(user.getEmail());
        setActive(user.isEnabled());
        var rolesSet = new HashSet<String>();
        user.getGroupsStream().flatMap(g -> g.getRoleMappingsStream())
                .filter((r) -> r.getFirstAttribute("scim").equals("true")).map((r) -> r.getName())
                .forEach(r -> rolesSet.add(r));

        user.getRoleMappingsStream().filter((r) -> {
            var attr = r.getFirstAttribute("scim");
            if (attr == null) {
                return false;
            }
            return attr.equals("true");
        }).map((r) -> r.getName()).forEach(r -> rolesSet.add(r));

        var roles = new String[rolesSet.size()];
        rolesSet.toArray(roles);
        setRoles(roles);
    }

    @Override
    public void apply(UserResource user) {
        setExternalId(user.getId());
        setUsername(user.getUserName());
        setDisplayName(user.getDisplayName());
        setActive(user.getActive());
        if (user.getEmails().size() > 0) {
            setEmail(user.getEmails().get(0).getValue());
        }
    }

    @Override
    public UserResource toSCIM(Boolean addMeta) {
        var user = new UserResource();
        user.setExternalId(id);
        user.setUserName(username);
        user.setId(externalId);
        user.setDisplayName(displayName);
        var emails = new ArrayList<Email>();
        if (email != null) {
            emails.add(
                    new Email().setPrimary(true).setValue(email));
        }
        user.setEmails(emails);
        user.setActive(active);
        if (addMeta) {
            var meta = new Meta();
            try {
                var uri = new URI("Users/" + externalId);
                meta.setLocation(uri);
            } catch (URISyntaxException e) {
            }
            user.setMeta(meta);
        }
        List<Role> roles = new ArrayList<Role>();
        for (var r : this.roles) {
            var role = new Role();
            role.setValue(r);
            roles.add(role);
        }
        user.setRoles(roles);
        return user;
    }

    @Override
    public void createEntity() {
        var user = session.users().addUser(realm, username);
        user.setEmail(email);
        user.setEnabled(active);
        this.id = user.getId();
    }

    @Override
    public Boolean entityExists() {
        if (this.id == null) {
            return false;
        }
        var user = session.users().getUserById(realm, id);
        if (user != null) {
            return true;
        }
        return false;
    }

    @Override
    public Boolean tryToMap() {
        var sameUsernameUser = session.users().getUserByUsername(realm, username);
        var sameEmailUser = session.users().getUserByEmail(realm, email);
        if ((sameUsernameUser != null && sameEmailUser != null) && sameUsernameUser.getId() != sameEmailUser.getId()) {
            LOGGER.warnf("found 2 possible users for remote user %s %s", username, email);
            return false;
        }
        if (sameUsernameUser != null) {
            this.id = sameUsernameUser.getId();
            return true;
        }
        if (sameEmailUser != null) {
            this.id = sameEmailUser.getId();
            return true;
        }
        return false;
    }

    @Override
    public Stream<UserModel> getResourceStream() {
        return this.session.users().getUsersStream(this.session.getContext().getRealm());
    }

    @Override
    public Boolean skipRefresh() {
        return getUsername().equals("admin");
    }
}

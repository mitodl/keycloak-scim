package sh.libre.scim.core;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.stream.Stream;

import de.captaingoldfish.scim.sdk.common.resources.User;
import de.captaingoldfish.scim.sdk.common.resources.multicomplex.Email;
import de.captaingoldfish.scim.sdk.common.resources.complex.Name;
import de.captaingoldfish.scim.sdk.common.resources.multicomplex.PersonRole;
import de.captaingoldfish.scim.sdk.common.resources.complex.Meta;


import org.apache.commons.lang.StringUtils;
import org.jboss.logging.Logger;
import org.keycloak.models.KeycloakSession;
import org.keycloak.models.UserModel;

public class UserAdapter extends Adapter<UserModel, User> {

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
    public Class<User> getResourceClass() {
        return User.class;
    }

    @Override
    public void apply(UserModel user) {
        setId(user.getId());
        setUsername(user.getUsername());
        var displayName = String.format("%s %s", StringUtils.defaultString(user.getFirstName()),
                StringUtils.defaultString(user.getLastName())).trim();
        if (StringUtils.isEmpty(displayName)) {
            displayName = user.getUsername();
        }
        setDisplayName(displayName);
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
        this.skip = StringUtils.equals(user.getFirstAttribute("scim-skip"), "true");
    }

    @Override
    public void apply(User user) {
        setExternalId(user.getId().get());
        setUsername(user.getUserName().get());
        setDisplayName(user.getDisplayName().get());
        setActive(user.isActive().get());
        if (user.getEmails().size() > 0) {
            setEmail(user.getEmails().get(0).getValue().get());
        }
    }

    @Override
    public User toSCIM(Boolean addMeta) {
        var user = new User();
        user.setExternalId(id);
        user.setUserName(username);
        user.setId(externalId);
        user.setDisplayName(displayName);
        Name name = new Name();
        user.setName(name);
        var emails = new ArrayList<Email>();
        if (email != null) {
            emails.add(
                Email.builder().value(getEmail()).build());
        }
        user.setEmails(emails);
        user.setActive(active);
        if (addMeta) {
            var meta = new Meta();
            try {
                var uri = new URI("Users/" + externalId);
              meta.setLocation(uri.toString());
            } catch (URISyntaxException e) {
            }
            user.setMeta(meta);
        }
        List<PersonRole> roles = new ArrayList<PersonRole>();
        for (var r : this.roles) {
            var role = new PersonRole();
            role.setValue(r);
            roles.add(role);
        }
        user.setRoles(roles);
        return user;
    }

    @Override
    public void createEntity() throws Exception {
        if (StringUtils.isEmpty(username)) {
            throw new Exception("can't create user with empty username");
        }
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
        UserModel sameUsernameUser = null;
        UserModel sameEmailUser = null;
        if (username != null) {
            sameUsernameUser = session.users().getUserByUsername(realm, username);
        }
        if (email != null) {
            sameEmailUser = session.users().getUserByEmail(realm, email);
        }
        if ((sameUsernameUser != null && sameEmailUser != null)
                && (sameUsernameUser.getId() != sameEmailUser.getId())) {
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

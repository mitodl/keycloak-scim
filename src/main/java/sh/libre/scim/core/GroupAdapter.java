package sh.libre.scim.core;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;

import javax.persistence.NoResultException;

import com.unboundid.scim2.common.types.GroupResource;
import com.unboundid.scim2.common.types.Member;
import com.unboundid.scim2.common.types.Meta;

import org.jboss.logging.Logger;
import org.keycloak.models.GroupModel;
import org.keycloak.models.KeycloakSession;

public class GroupAdapter extends Adapter<GroupModel, GroupResource> {

    private String displayName;
    private Set<String> members = new HashSet<String>();

    public GroupAdapter(KeycloakSession session, String componentId) {
        super(session, componentId, "Group", Logger.getLogger(GroupAdapter.class));
    }

    public String getDisplayName() {
        return displayName;
    }

    public void setDisplayName(String displayName) {
        if (this.displayName == null) {
            this.displayName = displayName;
        }
    }

    @Override
    public Class<GroupResource> getResourceClass() {
        return GroupResource.class;
    }

    @Override
    public void apply(GroupModel group) {
        setId(group.getId());
        setDisplayName(group.getName());
        this.members = session.users()
                .getGroupMembersStream(session.getContext().getRealm(), group)
                .map(x -> x.getId())
                .collect(Collectors.toSet());
    }

    @Override
    public void apply(GroupResource group) {
        setExternalId(group.getId());
        setDisplayName(group.getDisplayName());
        var groupMembers = group.getMembers();
        if (groupMembers != null && groupMembers.size() > 0) {
            this.members = new HashSet<String>();
            for (var groupMember : groupMembers) {
                var userMapping = this.query("findByExternalId", groupMember.getValue(), "User")
                        .getSingleResult();
                this.members.add(userMapping.getId());
            }
        }
    }

    @Override
    public GroupResource toSCIM(Boolean addMeta) {
        var group = new GroupResource();
        group.setId(externalId);
        group.setExternalId(id);
        group.setDisplayName(displayName);
        if (members.size() > 0) {
            var groupMembers = new ArrayList<Member>();
            for (var member : members) {
                var groupMember = new Member();
                try {
                    var userMapping = this.query("findById", member, "User").getSingleResult();
                    groupMember.setValue(userMapping.getExternalId());
                    var ref = new URI(String.format("Users/%s", userMapping.getExternalId()));
                    groupMember.setRef(ref);
                    groupMembers.add(groupMember);
                } catch (Exception e) {
                    LOGGER.error(e);
                }
            }
            group.setMembers(groupMembers);
        }
        if (addMeta) {
            var meta = new Meta();
            try {
                var uri = new URI("Groups/" + externalId);
                meta.setLocation(uri);
            } catch (URISyntaxException e) {
            }
            group.setMeta(meta);
        }
        return group;
    }

    @Override
    public Boolean entityExists() {
        if (this.id == null) {
            return false;
        }
        var group = session.groups().getGroupById(realm, id);
        if (group != null) {
            return true;
        }
        return false;
    }

    @Override
    public Boolean tryToMap() {
        var group = session.groups().getGroupsStream(realm).filter(x -> x.getName() == displayName).findFirst();
        if (group.isPresent()) {
            setId(group.get().getId());
            return true;
        }
        return false;
    }

    @Override
    public void createEntity() {
        var group = session.groups().createGroup(realm, displayName);
        this.id = group.getId();
        for (String mId : members) {
            try {
                var user = session.users().getUserById(realm, mId);
                if (user == null) {
                    throw new NoResultException();
                }
                user.joinGroup(group);
            } catch (Exception e) {
                LOGGER.warn(e);
            }
        }
    }

    @Override
    public Stream<GroupModel> getResourceStream() {
        return this.session.groups().getGroupsStream(this.session.getContext().getRealm());
    }

    @Override
    public Boolean skipRefresh() {
        return false;
    }

}

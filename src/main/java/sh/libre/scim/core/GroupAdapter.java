package sh.libre.scim.core;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import jakarta.persistence.NoResultException;

import de.captaingoldfish.scim.sdk.client.ScimRequestBuilder;
import de.captaingoldfish.scim.sdk.client.builder.PatchBuilder;
import de.captaingoldfish.scim.sdk.common.constants.enums.PatchOp;
import de.captaingoldfish.scim.sdk.common.resources.Group;
import de.captaingoldfish.scim.sdk.common.resources.multicomplex.Member;
import de.captaingoldfish.scim.sdk.common.resources.complex.Meta;
import org.apache.commons.lang3.StringUtils;
import org.jboss.logging.Logger;
import org.keycloak.models.GroupModel;
import org.keycloak.models.KeycloakSession;

public class GroupAdapter extends Adapter<GroupModel, Group> {

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
    public Class<Group> getResourceClass() {
        return Group.class;
    }

    @Override
    public void apply(GroupModel group) {
        setId(group.getId());
        setDisplayName(group.getName());
        this.members = session.users()
                .getGroupMembersStream(session.getContext().getRealm(), group)
                .map(x -> x.getId())
                .collect(Collectors.toSet());
        LOGGER.info(String.format("Collected %d members for group %s (id=%s)",
                this.members.size(), group.getName(), group.getId()));
        this.skip = StringUtils.equals(group.getFirstAttribute("scim-skip"), "true");
    }

    @Override
    public void apply(Group group) {
        setExternalId(group.getId().get());
        setDisplayName(group.getDisplayName().get());
        var groupMembers = group.getMembers();
        this.members = new HashSet<String>();
        if (groupMembers != null && groupMembers.size() > 0) {
            LOGGER.info(String.format("Processing %d incoming members for group %s",
                    groupMembers.size(), getDisplayName()));
            for (var groupMember : groupMembers) {
                try {
                    String memberValue = groupMember.getValue().get();
                    LOGGER.info(String.format("Looking up user with externalId: %s", memberValue));
                    var userMapping = this.query("findByExternalId", memberValue, "User")
                            .getSingleResult();
                    LOGGER.info(String.format("Found user mapping: id=%s, externalId=%s",
                            userMapping.getId(), userMapping.getExternalId()));
                    this.members.add(userMapping.getId());
                } catch (NoResultException e) {
                    LOGGER.error(String.format("No user mapping found for externalId: %s",
                            groupMember.getValue().get()));
                } catch (Exception e) {
                    LOGGER.error(String.format("Failed to process incoming group member: %s - Error: %s",
                            groupMember.getValue().get(), e.getMessage()), e);
                }
            }
        }
        LOGGER.info(String.format("Processed incoming SCIM group %s with %d mapped members",
                getDisplayName(), this.members.size()));
    }

    @Override
    public Group toSCIM(Boolean addMeta) {
        var group = new Group();
        group.setId(externalId);
        group.setExternalId(id);
        group.setDisplayName(displayName);

        List<Member> groupMembers = new ArrayList<>();
        LOGGER.info(String.format("Processing %d members for SCIM group %s", members.size(), displayName));

        for (String memberId : members) {
            try {
                // Try to ensure the user has a mapping
                String externalId = ensureUserMapping(memberId);

                if (externalId == null) {
                    LOGGER.error(String.format("Could not get or create mapping for user %s, skipping", memberId));
                    continue;
                }

                LOGGER.info(String.format("Adding member with externalId %s to group %s", externalId, displayName));
                var groupMember = new Member();
                groupMember.setValue(externalId);
                groupMember.setType("User");
                var ref = new URI(String.format("Users/%s", externalId));
                groupMember.setRef(ref.toString());
                groupMembers.add(groupMember);
            } catch (Exception e) {
                LOGGER.error("Failed to process group member: " + memberId, e);
            }
        }

        group.setMembers(groupMembers);
        LOGGER.info(String.format("Final SCIM group %s has %d members", displayName, groupMembers.size()));

        if (addMeta) {
            var meta = new Meta();
            try {
                var uri = new URI("Groups/" + externalId);
                meta.setLocation(uri.toString());
            } catch (URISyntaxException e) {
                LOGGER.error("Failed to create meta URI", e);
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
        LOGGER.info(String.format("Created new group: %s (id=%s)", displayName, this.id));

        for (String mId : members) {
            try {
                LOGGER.info(String.format("Attempting to add user with id=%s to group %s", mId, displayName));
                var user = session.users().getUserById(realm, mId);
                if (user == null) {
                    LOGGER.warn(String.format("User with id=%s not found in Keycloak", mId));
                    continue;
                }
                LOGGER.info(String.format("Adding user %s to group %s", user.getUsername(), displayName));
                user.joinGroup(group);
            } catch (Exception e) {
                LOGGER.warn(String.format("Failed to add user with id=%s to group: %s", mId, e.getMessage()), e);
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

    @Override
    public PatchBuilder<Group> toPatchBuilder(ScimRequestBuilder scimRequestBuilder, String url) {
        List<Member> groupMembers = new ArrayList<>();
        PatchBuilder<Group> patchBuilder;
        try {
            LOGGER.info(String.format("Creating PATCH request to URL: %s", url));
            patchBuilder = scimRequestBuilder.patch(url, Group.class);

            if (members.size() > 0) {
                for (String member : members) {
                    try {
                        LOGGER.info(String.format("Looking for member with id: %s", member));

                        // Try to ensure the user has a mapping
                        String externalId = ensureUserMapping(member);

                        if (externalId == null) {
                            LOGGER.error(
                                    String.format("Could not get or create mapping for user %s, skipping", member));
                            continue;
                        }

                        LOGGER.info(String.format("Using externalId %s for user %s", externalId, member));

                        Member memberNode = Member.builder()
                                .value(externalId)
                                .type("User")
                                .build();
                        groupMembers.add(memberNode);
                        LOGGER.info(String.format("Added member %s to PATCH request", externalId));
                    } catch (Exception e) {
                        LOGGER.error(String.format("Failed to process member %s: %s - Stack trace: ",
                                member, e.getMessage()), e);
                    }
                }

                // Debug the members being sent
                LOGGER.info(String.format("Adding %d members to PATCH request", groupMembers.size()));

                patchBuilder.addOperation()
                        .path("members")
                        .op(PatchOp.REPLACE)
                        .valueNodes(groupMembers)
                        .next()
                        .op(PatchOp.REPLACE)
                        .path("displayName")
                        .value(displayName)
                        .next()
                        .op(PatchOp.REPLACE)
                        .path("externalId")
                        .value(id)
                        .build();
            } else {
                LOGGER.info("No members to add, using REMOVE operation");
                patchBuilder.addOperation()
                        .path("members")
                        .op(PatchOp.REMOVE)
                        .value(null)
                        .next()
                        .op(PatchOp.REPLACE)
                        .path("displayName")
                        .value(displayName)
                        .next()
                        .op(PatchOp.REPLACE)
                        .path("externalId")
                        .value(id)
                        .build();
            }

            // Log the entire request for debugging
            LOGGER.info("Final PATCH request payload: " + patchBuilder.getResource());
            return patchBuilder;

        } catch (Exception e) {
            LOGGER.error(String.format("Failed to create patch request to %s: %s", url, e.getMessage()), e);
            throw e;
        }
    }

    /**
     * Ensures a user has a SCIM mapping, creating one if needed
     * 
     * @param userId The Keycloak user ID
     * @return The external SCIM ID for the user, or null if mapping failed
     */
    private String ensureUserMapping(String userId) {
        try {
            // First try to get the existing mapping
            LOGGER.info(String.format("Checking if user %s already has a SCIM mapping", userId));
            var userMappingQuery = this.query("findById", userId, "User");
            try {
                var existingMapping = userMappingQuery.getSingleResult();
                LOGGER.info(String.format("Found existing user mapping: id=%s, externalId=%s",
                        existingMapping.getId(), existingMapping.getExternalId()));
                return existingMapping.getExternalId();
            } catch (NoResultException e) {
                // No mapping found, need to create one
                LOGGER.info(String.format("No SCIM mapping found for user %s, creating one", userId));

                // Get the user from Keycloak
                var user = session.users().getUserById(realm, userId);
                if (user == null) {
                    LOGGER.error(String.format("Cannot create mapping: User %s not found in Keycloak", userId));
                    return null;
                }

                // Create a new UserAdapter to handle the mapping
                LOGGER.info(String.format("Creating UserAdapter for user %s (%s)", userId, user.getUsername()));
                try {
                    // Create a new User adapter and generate a mapping
                    var userAdapter = new UserAdapter(session, this.componentId);
                    userAdapter.setId(userId);

                    // Generate a new externalId (UUID) for this user
                    String externalId = java.util.UUID.randomUUID().toString();
                    userAdapter.setExternalId(externalId);

                    // Persist the mapping
                    LOGGER.info(String.format("Persisting new user mapping: id=%s, externalId=%s", userId, externalId));
                    userAdapter.saveMapping();

                    return externalId;
                } catch (Exception ex) {
                    LOGGER.error(String.format("Failed to create user mapping for %s: %s", userId, ex.getMessage()),
                            ex);
                    return null;
                }
            }
        } catch (Exception e) {
            LOGGER.error(String.format("Error ensuring user mapping for %s: %s", userId, e.getMessage()), e);
            return null;
        }
    }
}

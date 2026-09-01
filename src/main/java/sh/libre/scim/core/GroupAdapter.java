package sh.libre.scim.core;

import java.net.URI;
import java.net.URISyntaxException;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.regex.Pattern;
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
import org.keycloak.models.UserModel;

public class GroupAdapter extends Adapter<GroupModel, Group> {

    private String displayName;
    private Set<String> members = new HashSet<String>();
    // Remote members we could not translate to Keycloak users. Non-empty means this adapter's view of
    // membership is incomplete, so it must not be used to overwrite the server's.
    private List<String> unresolvedRemoteMembers = new ArrayList<>();

    // The group's CURRENT membership on the server, as returned by the SCIM API. Needed to compute a
    // delta: without it the only expressible operation is "replace everything", which deletes every
    // member Keycloak does not know about.
    private List<Member> remoteMembers = new ArrayList<>();

    // email -> remote user id, for Keycloak users that have no local SCIM mapping row yet. With
    // propagation-user = "false" the user adapter never runs, so NO mapping rows exist and this is the
    // only way membership can be resolved at all.
    private java.util.function.Function<String, String> remoteUserIdByEmail = email -> null;

    // remote user id -> email, so a membership change made on the SCIM server can be translated back
    // to a Keycloak user.
    private java.util.function.Function<String, String> emailByRemoteUserId = id -> null;

    /**
     * The member set as it was at the end of the last successful sync, stored on the Keycloak group as
     * the attribute below.
     *
     * 🚨 This is the whole point of the three-way merge. Comparing only "Keycloak now" against
     * "server now" cannot tell these apart:
     *     Keycloak {A,B} / server {A}  =  B was just ADDED in Keycloak   -> push B
     *     Keycloak {A,B} / server {A}  =  B was just REMOVED on the server -> drop B locally
     * Identical states, opposite correct actions. The baseline is the third point of reference that
     * makes them distinguishable.
     *
     * null means "no baseline yet" - first sync for this group - which is treated as a UNION so that
     * nobody loses membership on the changeover.
     */
    public static final String BASELINE_ATTR = "scim-last-synced-members";
    private Set<String> baseline = null;

    // Outcome of the merge, for the caller to apply to Keycloak.
    private final Set<String> localAdditions = new HashSet<>();
    private final Set<String> localRemovals = new HashSet<>();
    // The member set both sides should agree on once this sync completes - the next baseline.
    private Set<String> agreedMembers = new HashSet<>();

    public void setEmailByRemoteUserId(java.util.function.Function<String, String> f) {
        if (f != null) {
            this.emailByRemoteUserId = f;
        }
    }

    public void setBaseline(Set<String> baseline) {
        this.baseline = baseline;
    }

    public Set<String> getLocalAdditions() {
        return localAdditions;
    }

    public Set<String> getLocalRemovals() {
        return localRemovals;
    }

    public Set<String> getAgreedMembers() {
        return agreedMembers;
    }

    public String emailForRemoteId(String remoteId) {
        return emailByRemoteUserId.apply(remoteId);
    }

    private final List<PatchOpSpec> pendingOps = new ArrayList<>();

    /** One patch operation to send: either an ADD of members, or a targeted REMOVE of one member. */
    private record PatchOpSpec(PatchOp op, String path, List<Member> values) {}

    public void setRemoteMembers(List<Member> remoteMembers) {
        this.remoteMembers = remoteMembers == null ? new ArrayList<>() : remoteMembers;
    }

    public void setRemoteUserIdByEmail(java.util.function.Function<String, String> resolver) {
        if (resolver != null) {
            this.remoteUserIdByEmail = resolver;
        }
    }

    /** True if a PATCH is actually needed. A patch with zero operations is not a valid SCIM request. */
    public boolean hasMembershipChanges() {
        return !pendingOps.isEmpty();
    }

    public List<String> getUnresolvedRemoteMembers() {
        return unresolvedRemoteMembers;
    }

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
        this.skip = StringUtils.equals(group.getFirstAttribute("scim-skip"), "true");
        String stored = group.getFirstAttribute(BASELINE_ATTR);
        this.baseline = (stored == null || stored.isBlank())
                ? null
                : new HashSet<>(Arrays.asList(stored.split(",")));
    }

    @Override
    public void apply(Group group) {
        setExternalId(group.getId().get());
        setDisplayName(group.getDisplayName().get());
        var groupMembers = group.getMembers();
        if (groupMembers != null && groupMembers.size() > 0) {
            // 🚨 Do NOT assign to this.members until every remote member has been resolved.
            //
            // This used to clear this.members first and then populate it, swallowing per-member
            // failures. apply(GroupModel) runs BEFORE this and fills this.members from Keycloak, so a
            // remote member whose user mapping was missing did not just fail to be added - it wiped
            // the local membership that had already been collected. The caller then propagated that
            // empty set back to the server as a REPLACE, i.e. a deletion.
            var resolved = new HashSet<String>();
            var unresolved = new ArrayList<String>();
            for (var groupMember : groupMembers) {
                var remoteUserId = groupMember.getValue().get();
                try {
                    var mapping = query("findByExternalId", remoteUserId, "User").getSingleResult();
                    resolved.add(mapping.getId());
                } catch (Exception e) {
                    unresolved.add(remoteUserId);
                }
            }
            if (unresolved.isEmpty()) {
                this.members = resolved;
            } else {
                // Keep whatever apply(GroupModel) gave us. Losing the ability to mirror remote
                // membership is an inconvenience; overwriting local membership with a partial view is
                // data loss, so prefer the former and say so.
                this.unresolvedRemoteMembers = unresolved;
                LOGGER.warnf(
                        "Group %s: %d of %d remote member(s) have no local user mapping (%s). Keeping the "
                                + "Keycloak-side membership rather than overwriting it with a partial set.",
                        displayName, unresolved.size(), groupMembers.size(), String.join(", ", unresolved));
            }
        }
    }


    /**
     * Keycloak user -> remote user id.
     *
     * Order matters: the local SCIM mapping row first (authoritative when it exists), then a lookup by
     * email. The email path is what makes membership work at all with propagation-user = "false",
     * because in that mode the user adapter never runs and no mapping rows are ever written.
     *
     * Returns null when neither works. Callers must treat that as a refusal, never as "no member".
     */
    private String resolveRemoteUserId(UserModel user) {
        try {
            String mapped = query("findById", user.getId(), "User").getSingleResult().getExternalId();
            if (mapped != null && !mapped.isBlank()) {
                return mapped;
            }
        } catch (Exception ignored) {
            // no mapping row - fall through to email
        }
        return user.getEmail() == null ? null : remoteUserIdByEmail.apply(user.getEmail());
    }

    @Override
    public Group toSCIM(Boolean addMeta) {
        var group = new Group();
        group.setId(externalId);
        group.setExternalId(id);
        group.setDisplayName(displayName);
        if (members.size() > 0) {
            var groupMembers = new ArrayList<Member>();
            var unresolved = new ArrayList<String>();
            for (var member : members) {
                var user = session.users().getUserById(realm, member);
                if (user == null) {
                    unresolved.add(member + " (no such Keycloak user)");
                    continue;
                }
                String remoteId = resolveRemoteUserId(user);
                if (remoteId == null) {
                    unresolved.add(String.format("%s <%s>", user.getUsername(), user.getEmail()));
                    continue;
                }
                var groupMember = new Member();
                groupMember.setValue(remoteId);
                try {
                    groupMember.setRef(new URI(String.format("Users/%s", remoteId)).toString());
                } catch (URISyntaxException e) {
                    // $ref is optional; the value is what identifies the member.
                }
                groupMembers.add(groupMember);
            }
            if (!unresolved.isEmpty()) {
                // Still a refusal, not a silent drop - an absent member reads as a removal. But now it
                // only fires when BOTH the mapping row and the email lookup came up empty.
                throw new IllegalStateException(String.format(
                        "Cannot serialise group '%s': %d of %d member(s) could not be resolved to a remote "
                                + "user, by SCIM mapping or by email (%s).",
                        displayName, unresolved.size(), members.size(), String.join("; ", unresolved)));
            }
            group.setMembers(groupMembers);
        }
        if (addMeta) {
            var meta = new Meta();
            try {
                meta.setLocation(new URI("Groups/" + externalId).toString());
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
        // `==` here compared String REFERENCES, so this never matched and map-existing-groups could
        // not work: every sync fell through to "create", got "Group already exists" from the server,
        // and no mapping row was ever persisted.
        var group = session.groups().getGroupsStream(realm)
                .filter(x -> StringUtils.equals(x.getName(), displayName)).findFirst();
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
        return getFilteredGroups();
    }

    @Override
    public Boolean skipRefresh() {
        return false;
    }

    /**
     * Builds the membership PATCH for this group as a DELTA, never as a wholesale replace.
     *
     * 🚨 Why not REPLACE. The previous implementation sent
     * {@code {"op":"replace","path":"members","value":[...]}}, which means "this list IS the group".
     * Anything absent from it is deleted server-side. Two ways that goes wrong against the Databricks
     * account:
     *
     *  1. Members that are not Keycloak users at all. `databricks-developers-prod` contains the service
     *     principal `su-dsv-bedrock-dbx-prod` ($ref "ServicePrincipals/..."), and other groups contain
     *     users whose membership is declared by Terraform. Keycloak cannot resolve any of them, so a
     *     REPLACE removes them and breaks automation. Terraform then re-adds on its next apply and the
     *     next sync removes them again - a flip-flop with prod access broken in the gaps.
     *  2. A resolution failure is indistinguishable from a removal - the failure mode that emptied a
     *     group's member list on 2026-08-21.
     *
     * So: compute what changed, touch only that, and leave everything else on the server alone.
     * Members whose $ref is not "Users/..." are never candidates for removal.
     */
    @Override
    public PatchBuilder<Group> toPatchBuilder(ScimRequestBuilder scimRequestBuilder, String url) {
        pendingOps.clear();

        if (!unresolvedRemoteMembers.isEmpty()) {
            throw new IllegalStateException(String.format(
                    "Refusing to PATCH members of group '%s': %d remote member(s) could not be mapped to "
                            + "Keycloak users (%s), so the local view is incomplete.",
                    displayName, unresolvedRemoteMembers.size(), String.join(", ", unresolvedRemoteMembers)));
        }

        // What Keycloak says the membership should be, as remote ids.
        Set<String> desired = new HashSet<>();
        List<String> unresolved = new ArrayList<>();
        for (String member : members) {
            var user = session.users().getUserById(realm, member);
            if (user == null) {
                unresolved.add(member + " (no such Keycloak user)");
                continue;
            }
            String remoteId = resolveRemoteUserId(user);
            if (remoteId == null) {
                unresolved.add(String.format("%s <%s>", user.getUsername(), user.getEmail()));
            } else {
                desired.add(remoteId);
            }
        }

        if (!unresolved.isEmpty()) {
            throw new IllegalStateException(String.format(
                    "Refusing to PATCH members of group '%s': %d of %d member(s) could not be resolved to a "
                            + "remote user, by SCIM mapping or by email (%s).",
                    displayName, unresolved.size(), members.size(), String.join("; ", unresolved)));
        }

        // What is on the server now, split into "things Keycloak may manage" and "everything else".
        Set<String> currentUsers = new HashSet<>();
        List<String> preserved = new ArrayList<>();
        for (Member m : remoteMembers) {
            String value = m.getValue().orElse(null);
            if (value == null) {
                continue;
            }
            String ref = m.getRef().orElse("");
            if (ref.startsWith("Users/") || ref.isEmpty()) {
                currentUsers.add(value);
            } else {
                // Service principals and any other member type. Never a removal candidate.
                preserved.add(m.getDisplay().orElse(value) + " (" + ref.split("/")[0] + ")");
            }
        }
        if (!preserved.isEmpty()) {
            LOGGER.infof("Group %s: preserving %d non-user member(s) not managed by Keycloak: %s",
                    displayName, preserved.size(), String.join(", ", preserved));
        }

        // ---- three-way merge: baseline (last synced) vs Keycloak (desired) vs server (currentUsers) ----
        localAdditions.clear();
        localRemovals.clear();
        List<Member> toAdd;
        List<String> toRemove;

        if (baseline == null) {
            // First sync for this group: no history, so no change can be attributed to either side.
            // UNION - add what each side is missing, remove nothing. Nobody loses membership on the
            // changeover, and the baseline written afterwards makes the next sync precise.
            toAdd = desired.stream().filter(id -> !currentUsers.contains(id))
                    .map(id -> Member.builder().value(id).build()).collect(Collectors.toList());
            toRemove = new ArrayList<>();
            currentUsers.stream().filter(id -> !desired.contains(id)).forEach(localAdditions::add);
            agreedMembers = new HashSet<>(desired);
            agreedMembers.addAll(currentUsers);
            LOGGER.infof("Group %s: no baseline yet, treating as UNION (+%d remote, +%d local)",
                    displayName, toAdd.size(), localAdditions.size());
        } else {
            // Attribute each difference to the side that actually changed.
            Set<String> addedInKeycloak = desired.stream().filter(id -> !baseline.contains(id))
                    .collect(Collectors.toSet());
            Set<String> removedInKeycloak = baseline.stream().filter(id -> !desired.contains(id))
                    .collect(Collectors.toSet());
            Set<String> addedOnServer = currentUsers.stream().filter(id -> !baseline.contains(id))
                    .collect(Collectors.toSet());
            Set<String> removedOnServer = baseline.stream().filter(id -> !currentUsers.contains(id))
                    .collect(Collectors.toSet());

            // Push what Keycloak changed; pull what the server changed.
            toAdd = addedInKeycloak.stream().filter(id -> !currentUsers.contains(id))
                    .map(id -> Member.builder().value(id).build()).collect(Collectors.toList());
            toRemove = removedInKeycloak.stream().filter(currentUsers::contains).collect(Collectors.toList());
            addedOnServer.stream().filter(id -> !desired.contains(id)).forEach(localAdditions::add);
            removedOnServer.stream().filter(desired::contains).forEach(localRemovals::add);

            // Same member changed on both sides in opposite directions. Nothing in the data says which
            // is newer, so pick the safe one - a removal wins over an addition. Losing access is
            // recoverable in seconds; keeping access that someone tried to revoke is not.
            Set<String> conflicted = new HashSet<>(addedInKeycloak);
            conflicted.retainAll(removedOnServer);
            if (!conflicted.isEmpty()) {
                LOGGER.warnf("Group %s: %d member(s) added in Keycloak but removed on the server "
                        + "(%s). Treating the REMOVAL as authoritative.",
                        displayName, conflicted.size(), String.join(", ", conflicted));
                toAdd.removeIf(m -> conflicted.contains(m.getValue().orElse("")));
                localRemovals.addAll(conflicted);
            }

            agreedMembers = new HashSet<>(desired);
            agreedMembers.addAll(localAdditions);
            agreedMembers.removeAll(localRemovals);
            LOGGER.infof("Group %s: merge vs baseline of %d - keycloak(+%d/-%d) server(+%d/-%d)",
                    displayName, baseline.size(), addedInKeycloak.size(), removedInKeycloak.size(),
                    addedOnServer.size(), removedOnServer.size());
        }

        if (!toAdd.isEmpty()) {
            pendingOps.add(new PatchOpSpec(PatchOp.ADD, "members", toAdd));
        }
        for (String id : toRemove) {
            // SCIM 2.0 targeted removal. Removing by value filter cannot affect any other member.
            pendingOps.add(new PatchOpSpec(PatchOp.REMOVE, String.format("members[value eq \"%s\"]", id), null));
        }

        PatchBuilder<Group> patchBuilder = scimRequestBuilder.patch(url, Group.class);
        for (PatchOpSpec spec : pendingOps) {
            var op = patchBuilder.addOperation().path(spec.path()).op(spec.op());
            if (spec.values() != null) {
                op.valueNodes(spec.values());
            }
            op.build();
        }
        LOGGER.infof("Group %s membership delta: +%d -%d (server had %d user member(s), %d preserved)",
                displayName, toAdd.size(), toRemove.size(), currentUsers.size(), preserved.size());
        if (!pendingOps.isEmpty()) {
            LOGGER.info(patchBuilder.getResource());
        }
        return patchBuilder;
    }

}

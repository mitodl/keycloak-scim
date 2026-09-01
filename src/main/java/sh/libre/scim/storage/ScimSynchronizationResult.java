package sh.libre.scim.storage;

import java.util.ArrayList;
import java.util.List;

import org.keycloak.storage.user.SynchronizationResult;

public class ScimSynchronizationResult extends SynchronizationResult {
    private List<String> addedUsers = new ArrayList<>();
    private List<String> updatedUsers = new ArrayList<>();
    private List<String> removedUsers = new ArrayList<>();
    private List<String> failedUsers = new ArrayList<>();
    private List<String> mappedUsers = new ArrayList<>();
    private List<String> addedGroups = new ArrayList<>();
    private List<String> updatedGroups = new ArrayList<>();
    private List<String> removedGroups = new ArrayList<>();
    private List<String> failedGroups = new ArrayList<>();
    private List<String> mappedGroups = new ArrayList<>();

    public void addAddedUser(String userInfo) {
        addedUsers.add(userInfo);
        super.increaseAdded();
    }

    public void addUpdatedUser(String userInfo) {
        updatedUsers.add(userInfo);
        super.increaseUpdated();
    }

    public void addRemovedUser(String userInfo) {
        removedUsers.add(userInfo);
        super.increaseRemoved();
    }

    public void addFailedUser(String userInfo) {
        failedUsers.add(userInfo);
        super.increaseFailed();
    }

    public void addAddedGroup(String groupInfo) {
        addedGroups.add(groupInfo);
        super.increaseAdded();
    }

    public void addUpdatedGroup(String groupInfo) {
        updatedGroups.add(groupInfo);
        super.increaseUpdated();
    }

    public void addRemovedGroup(String groupInfo) {
        removedGroups.add(groupInfo);
        super.increaseRemoved();
    }

    public void addFailedGroup(String groupInfo) {
        failedGroups.add(groupInfo);
        super.increaseFailed();
    }

    public void addMappedUser(String userInfo) {
        mappedUsers.add(userInfo);
        super.increaseUpdated(); // Treat as updated
    }

    public void addMappedGroup(String groupInfo) {
        mappedGroups.add(groupInfo);
        super.increaseUpdated(); // Treat as updated
    }

    // Getters for the lists
    public List<String> getAddedUsers() { return addedUsers; }
    public List<String> getUpdatedUsers() { return updatedUsers; }
    public List<String> getRemovedUsers() { return removedUsers; }
    public List<String> getFailedUsers() { return failedUsers; }
    public List<String> getMappedUsers() { return mappedUsers; }
    public List<String> getAddedGroups() { return addedGroups; }
    public List<String> getUpdatedGroups() { return updatedGroups; }
    public List<String> getRemovedGroups() { return removedGroups; }
    public List<String> getFailedGroups() { return failedGroups; }
    public List<String> getMappedGroups() { return mappedGroups; }

    @Override
    public String getStatus() {
        StringBuilder status = new StringBuilder();
        if (getAdded() > 0) status.append(getAdded()).append(" added");
        if (getUpdated() > 0) {
            if (status.length() > 0) status.append(", ");
            status.append(getUpdated()).append(" updated");
        }
        if (getRemoved() > 0) {
            if (status.length() > 0) status.append(", ");
            status.append(getRemoved()).append(" removed");
        }
        if (getFailed() > 0) {
            if (status.length() > 0) status.append(", ");
            status.append(getFailed()).append(" failed");
        }
        int mapped = mappedUsers.size() + mappedGroups.size();
        if (mapped > 0) {
            if (status.length() > 0) status.append(", ");
            status.append(mapped).append(" mapped");
        }
        if (status.length() == 0) status.append("No changes");
        return status.toString();
    }
}
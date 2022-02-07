package sh.libre.scim.storage;

import org.keycloak.models.RealmModel;
import org.keycloak.models.UserModel;
import org.keycloak.storage.UserStorageProvider;
import org.keycloak.storage.user.UserRegistrationProvider;

public class ScimStorageProvider implements UserStorageProvider, UserRegistrationProvider {
    @Override
    public void close() {
    }

    @Override
    public UserModel addUser(RealmModel realm, String username) {
        // TODO Auto-generated method stub
        return null;
    }

    @Override
    public boolean removeUser(RealmModel realm, UserModel user) {
        // TODO Auto-generated method stub
        return false;
    }

}

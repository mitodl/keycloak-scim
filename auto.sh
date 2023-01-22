gradle jar shadowjar
scp build/libs/keycloak-scim-1.0-SNAPSHOT-all.jar root@192.168.130.252:/var/www/html/keycloak-scim-1.0-SNAPSHOT-all.jar
scp build/libs/keycloak-scim-1.0-SNAPSHOT-all.jar root@192.168.130.252:/var/www/html/keycloak-scim-aws-1.0-SNAPSHOT-all.jar
k delete pod keycloak-keycloakx-0 -n keycloak

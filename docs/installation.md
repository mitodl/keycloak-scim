# Installation (advanced)

## 1. Download the jar files

### All-in-one

This [package](<(https://lab.libreho.st/libre.sh/scim/keycloak-scim/-/jobs/artifacts/main/raw/build/libs/keycloak-scim-1.0-SNAPSHOT-all.jar?job=package)>) contains the SCIM provider and it's dependencies. It's intended only for Quarkus distribution.

### Separately

You'll need the SCIM provider's [package](<(https://lab.libreho.st/libre.sh/scim/keycloak-scim/-/jobs/artifacts/main/raw/build/libs/keycloak-scim-1.0-SNAPSHOT.jar?job=package)>) and each of its [dependencies](dependencies.md).

## 2. Install the jar files

### Quarkus

Copy the downloaded file in `/opt/keycloak/providers/`. For production use, build Keycloak before starting it.

### Wildfly (legacy)

Copy the downloaded file in `/opt/jboss/keycloak/standalone/deployments/`.

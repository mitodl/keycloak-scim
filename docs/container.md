# Container

## Quarkus

```
FROM quay.io/keycloak/keycloak as builder
RUN curl "https://lab.libreho.st/libre.sh/scim/keycloak-scim/-/jobs/artifacts/main/raw/build/libs/keycloak-scim-1.0-SNAPSHOT-all.jar?job=package" -Lo /opt/keycloak/providers/keycloak-scim-1.0-SNAPSHOT-all.jar
RUN /opt/keycloak/bin/kc.sh build

FROM quay.io/keycloak/keycloak
COPY --from=builder /opt/keycloak/ /opt/keycloak/
WORKDIR /opt/keycloak
ENTRYPOINT ["/opt/keycloak/bin/kc.sh"]
CMD ["start"]
```

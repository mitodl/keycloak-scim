# keycloak-scim-client

This extension add [SCIM2](http://www.simplecloud.info) client capabilities to Keycloak. (See [RFC7643](https://datatracker.ietf.org/doc/html/rfc7643) and [RFC7644](https://datatracker.ietf.org/doc/html/rfc7644)).

## Overview

### Motivation

We want to build a unified collaborative platform based on multiple applications. To do that, we need a way to propagate immediately changes made in Keycloak to all these applications. And we want to keep using OIDC or SAML as the authentication protocol.

This will allow users to collaborate seamlessly across the platform without requiring every user to have connected once to each application. This will also ease GDRP compliance because deleting a user in Keycloak will delete the user from every app.

### Technical choices

The SCIM protocol is standard, comprehensible and easy to implement. It's a perfect fit for our goal.

We chose to build application extensions/plugins because it's easier to deploy and thus will benefit to a larger portion of the FOSS community.

#### Keycloak specific

This extension uses 3 concepts in KC :
- Event Listener : it's used to listens for changes and transform them in SCIM calls.
- Federation Provider : it's used to set up all the SCIM service providers without creating our own UI.
- JPA Entity Provider : it's used to save the mapping between the local IDs and the service providers IDs.

Because the event listener is the source of the SCIM flow, and it is not cancelable, we can't have strictly consistent behavior in case of SCIM service provider failure. 

## Usage

### Installation

> For now, it's doesn't't work on Quarkus which is the default after version 16.x.x. 

1. Download the [latest version](https://lab.libreho.st/libre.sh/scim/keycloak-scim/-/jobs/artifacts/main/raw/target/keycloak-scim-1.0-SNAPSHOT-jar-with-dependencies.jar?job=package)
2. Put it in `/opt/jboss/keycloak/standalone/deployments/`.

### Setup

#### Add the event listerner

1. Go to `Admin Console > Events > Config`.
2. Add `scim` in `Event Listeners`.
3. Save.

![Event listener page](/docs/img/event-listener-page.png)

#### Create a federation provider

1. Go to `Admin Console > User Federation`.
2. Click on `Add provider`.
3. Select `scim`.
4. Configure the provider ([see](#configuration)).
5. Save.

![Federation provider page](/docs/img/federation-provider-page.png)

### Configuration

TODO

### Sync

TODO


**[License AGPL](/LICENSE)**

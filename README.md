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

### Installation (quick)

1. Download the [latest version](https://lab.libreho.st/libre.sh/scim/keycloak-scim/-/jobs/artifacts/main/raw/build/libs/keycloak-scim-1.0-SNAPSHOT-all.jar?job=package)
2. Put it in `/opt/keycloak/providers/`.

It's also possible to build your own custom image if you run Keycloak in a [container](/docs/container.md).

Other [installation options](/docs/installation.md) are available.

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

Add the endpoint - for a local set up you have to add the two containers in a docker network and use the container ip see [here](https://docs.docker.com/engine/reference/commandline/network/)
If you use the [rocketchat app](https://lab.libreho.st/libre.sh/scim/rocketchat-scim) you get the endpoint from your rocket Chat Scim Adapter App Details.
Endpoint content type is application/json.
Auth mode Bearer or None for local test setup.
Copy the bearer token from your app details in rocketchat.

If you enable import during sync then you can choose between to following import actions:
- Create Local - adds users to keycloak
- Nothing
- Delete Remote - deletes users from the remote application




### Sync

You can set up a periodic sync for all users or just changed users. You can either do:
- Periodic Full Sync
- Periodic Changed User Sync


---

## Building, and why the build shades so aggressively

Keycloak loads provider jars from `/opt/keycloak/providers` onto its **own** classpath. Any library
this fat jar bundles therefore **shadows Keycloak's copy of that library, for the whole server** —
not just for this plugin.

This is not theoretical. On **2026-08-20**, on Keycloak 26.4.6, v1.4 bundled `jackson-core 2.17.2`,
which took precedence over Keycloak's newer copy. Keycloak's `webauthn4j` is compiled against the
newer one, so at passkey verification it called a constructor that does not exist in 2.17.2 and
threw `NoSuchMethodError`. **Every WebAuthn/passkey login on the realm returned HTTP 500.** Password
and IdP logins were unaffected — which is exactly why no smoke test caught it.

So `build.gradle` makes a deliberate choice per bundled library:

| Library | Choice | Why |
|---|---|---|
| Jackson, Apache HttpClient, commons | **relocate** | `scim-sdk` needs the exact versions it was built against. A private copy under `sh.libre.scim.shaded.*` gives it that with no coupling either way. |
| BouncyCastle | **exclude** | Signed jar, and it registers a JCE provider under a fixed name. Relocation renames the provider classes and breaks both. Keycloak ships BC — use the server's. |
| Jakarta EE APIs (`jakarta.ws.rs`, `jakarta.persistence`) | **exclude**, and `compileOnly` at the versions Keycloak ships | These are the contract *between* the plugin and the server, so they must come from the server. `compileOnly` at the server's versions makes the compiler enforce that the plugin only uses API that exists at runtime. |
| `slf4j-api` | **exclude** | Bound at runtime to one provider via `ServiceLoader`. Keycloak ships `slf4j-api` plus `slf4j-jboss-logmanager`; a second api copy is how you get vanishing logs. |

### The three checks that keep it that way

All three run on every PR and again before a release is published
(`.github/workflows/validate.yaml`).

**1. `./gradlew check` — the fast offline guard.**
The `verifyNoClasspathPollution` task fails the build if a known-dangerous package prefix lands at
the root of the jar. It is wired both into `check` and as a `finalizedBy` on `shadowJar`, so a
release cannot skip it by running `shadowJar` directly. It checks a *hardcoded* list, so it only
knows about libraries someone already thought of — hence check 2.

**2. `.github/scripts/check-classpath-overlap.sh` — the durable one.**
Extracts `/opt/keycloak/lib` from the real Keycloak image and fails if *any* package in the plugin
jar also exists on Keycloak's classpath. The forbidden set is derived, not hardcoded, so a new
transitive dependency that happens to collide is caught by name on the PR that adds it.

This is the check that would have stopped the outage: run it against the v1.4 release jar and it
reports **410** colliding packages on 26.4.6 and **395** on 26.6.4.

**3. `.github/scripts/smoke-test-plugin.sh` — the complement.**
Boots a real Keycloak with the jar mounted and asserts the plugin still registers all three of its
SPIs, with no linkage errors in the log. Check 2 pushes toward removing things from the jar; this
one catches removing too much. Note it does **not** catch the outage on its own — v1.4 boots and
registers fine, because the breakage was at request time, in a code path only passkey login reaches.

Both scripts check **every** version listed in `keycloakServerVersions` in `gradle.properties`, and
both run locally against a built jar:

```sh
./gradlew check
./.github/scripts/check-classpath-overlap.sh
./.github/scripts/smoke-test-plugin.sh
```

### Releasing

`Build keycloak-scim and release` publishes a GitHub release named after `version` in
`build.gradle`. It runs the checks above first, and **will not republish a version that already
exists** — bump `version` to cut a new one.

That matters because consumers pin the asset by URL *and* sha256 (the Keycloak initContainer in
`daikin-dsv/deploy` verifies the digest and refuses to start the pod on a mismatch). Gradle jars are
not byte-reproducible, so republishing the same version behind the same URL would break every pinned
consumer.

It also means a release can be cut from a branch via `workflow_dispatch`, tested on a real cluster,
and merged afterwards — the merge run finds the version already published and stops, so the bytes
that were tested are the bytes that stay.

### When the target Keycloak versions change

`keycloakServerVersions` in `gradle.properties` is a **list**, and checks 2 and 3 run against every
entry. That is deliberate: a provider jar sits on the server's own classpath, so "this jar is safe"
is a claim about a specific server build — and the fleet runs several at once. During a blue/green
Keycloak upgrade one cluster serves two versions from a single values block, and production is
usually the *older* one. Validating only the newest would leave production unverified for exactly
the weeks an upgrade is in flight.

So: **add** a version before the upgrade reaches a cluster, and **remove** it once no cluster runs
it. Don't swap.

If check 2 starts failing on a newly added version, that server ships a library the plugin bundles —
relocate or exclude it using the table above. If the `compileOnly` Jakarta versions no longer match
what it ships, update those too.

To check a single version ad hoc, pass it explicitly:

```sh
./.github/scripts/check-classpath-overlap.sh build/libs/keycloak-scim-1.6.0-all.jar 26.6.4
```


**[License AGPL](/LICENSE)**

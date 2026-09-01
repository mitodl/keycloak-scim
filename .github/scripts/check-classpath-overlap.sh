#!/usr/bin/env bash
#
# Fails if the shaded plugin jar would shadow ANY library the Keycloak server already provides.
#
# Why this exists
# ---------------
# Keycloak loads provider jars from /opt/keycloak/providers onto its OWN classpath. A package that
# exists both in the plugin jar and in Keycloak's lib/ is therefore resolved from ONE of them for
# the whole server - and which one wins is not something the plugin controls.
#
# This went off in production on 2026-08-20 (Keycloak 26.4.6): the plugin bundled jackson-core
# 2.17.2, which shadowed Keycloak's newer copy. Keycloak's webauthn4j is compiled against the newer
# one, so every passkey login threw NoSuchMethodError and returned HTTP 500. Password and IdP logins
# were unaffected, which is exactly why nothing caught it before users did.
#
# build.gradle has a fast local guard (`verifyNoClasspathPollution`) but it checks a HARDCODED list
# of package prefixes, so it only knows about libraries someone already thought of. This check is
# the durable version: it derives the forbidden set from the real Keycloak distribution, so a new
# transitive dependency that happens to collide is caught the day it is added, by name, without
# anyone having to predict it.
#
# Usage: check-classpath-overlap.sh [path/to/plugin.jar] [keycloak-version]
# Defaults: the built shadow jar, and EVERY version in keycloakServerVersions (gradle.properties).
# Pass a version to check just that one.
set -euo pipefail

repo_root="$(cd "$(dirname "${BASH_SOURCE[0]}")/../.." && pwd)"

jar="${1:-}"
if [[ -z "$jar" ]]; then
  jar="$(ls -1 "$repo_root"/build/libs/*-all.jar 2>/dev/null | head -1 || true)"
fi
if [[ -z "$jar" || ! -f "$jar" ]]; then
  echo "error: no plugin jar found. Run './gradlew shadowJar' first, or pass the jar path." >&2
  exit 2
fi

# Versions to check: an explicit argument wins, otherwise every version in gradle.properties.
# A jar is only "safe" for a server build we actually tested it against, and the fleet runs several
# at once - so the default is ALL of them, not the newest.
versions_to_check() {
  if [[ -n "${1:-}" ]]; then
    echo "$1"
    return
  fi
  grep -E '^keycloakServerVersions=' "$repo_root/gradle.properties" | cut -d= -f2 | tr ',' ' '
}

kc_versions="$(versions_to_check "${2:-}")"
if [[ -z "${kc_versions// /}" ]]; then
  echo "error: no Keycloak versions to check (gradle.properties: keycloakServerVersions)." >&2
  exit 2
fi

# Everything the plugin is allowed to own. Relocated copies live under sh/libre/scim/shaded/, so
# this one prefix covers them too.
own_prefix="sh/libre/scim"

work="$(mktemp -d)"
trap 'rm -rf "$work"' EXIT

echo "plugin jar : $jar"
echo "versions   : $kc_versions"
echo

# Java packages inside a jar, one per line, as slash-separated paths.
#
# Multi-release jars keep a second copy of some classes under META-INF/versions/<N>/; strip that
# prefix so a collision hiding in there is still seen. Directory entries and top-level classes
# (no '/') are dropped.
packages_of() {
  unzip -Z1 "$1" 2>/dev/null \
    | grep '\.class$' \
    | sed -E 's#^META-INF/versions/[0-9]+/##' \
    | grep '/' \
    | sed 's#/[^/]*$##'
}

# The plugin's own package set does not change per Keycloak version - compute it once.
packages_of "$jar" | sort -u > "$work/plugin.txt"
echo "    $(wc -l < "$work/plugin.txt" | tr -d ' ') packages in the plugin jar"
echo

# Checks ONE Keycloak version. Returns non-zero on a collision; the caller keeps going so a run
# reports every bad version, not just the first.
check_version() {
  local kc_version="$1"
  local image="quay.io/keycloak/keycloak:${kc_version}"
  local d="$work/$kc_version"
  mkdir -p "$d"

  echo "==> Keycloak ${kc_version}: extracting the classpath from $image"
  docker pull -q "$image" >/dev/null
  local cid
  cid="$(docker create "$image")"
  docker cp "$cid:/opt/keycloak/lib" "$d/lib" >/dev/null
  docker rm -f "$cid" >/dev/null

  find "$d/lib" -name '*.jar' -print0 | xargs -0 -n1 -- bash -c 'unzip -Z1 "$0" 2>/dev/null || true' \
    | grep '\.class$' \
    | sed -E 's#^META-INF/versions/[0-9]+/##' \
    | grep '/' \
    | sed 's#/[^/]*$##' \
    | sort -u > "$d/keycloak.txt"

  local kc_jars
  kc_jars=$(find "$d/lib" -name '*.jar' | wc -l | tr -d ' ')
  echo "    $kc_jars jars, $(wc -l < "$d/keycloak.txt" | tr -d ' ') packages on Keycloak's classpath"

  comm -12 "$d/keycloak.txt" "$work/plugin.txt" | grep -v "^${own_prefix}" > "$d/overlap.txt" || true

  if [[ -s "$d/overlap.txt" ]]; then
    local total
    total=$(wc -l < "$d/overlap.txt" | tr -d ' ')
    echo "    FAIL: $total package(s) collide with Keycloak ${kc_version}:"
    sed 's#/#.#g; s#^#      - #' "$d/overlap.txt" | head -25
    if (( total > 25 )); then
      echo "      ... and $(( total - 25 )) more"
    fi
    return 1
  fi

  echo "    PASS: no collision with Keycloak ${kc_version}"
  return 0
}

failed=()
for v in $kc_versions; do
  check_version "$v" || failed+=("$v")
  echo
done

if (( ${#failed[@]} )); then
  echo "FAIL: the plugin jar bundles packages Keycloak also provides, on: ${failed[*]}"
  echo
  echo "Those packages would shadow Keycloak's own copies for the ENTIRE server, and can break"
  echo "server features that have nothing to do with SCIM."
  echo
  echo "Fix in build.gradle's shadowJar block, choosing per library:"
  echo "  * relocate(...)  - the plugin needs its own private copy (e.g. Jackson for scim-sdk)"
  echo "  * exclude(...)   - the server's copy is the right one, or is a shared contract"
  echo "                     (Jakarta EE APIs, slf4j-api, BouncyCastle's signed JCE provider)"
  exit 1
fi

echo "PASS: the plugin jar collides with none of: ${kc_versions}"

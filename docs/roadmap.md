# Roadmap

Post-1.0 backlog for the fork. 1.0.0 shipped on 2026-05-26 and the
1.0.x line is current (latest 1.0.2, 2026-06-07); release-please now
drives versioning from conventional commits, so this doc is
forward-looking only — for what landed, see
[`CHANGELOG.md`](../CHANGELOG.md); for the release flow, see
[`docs/releasing.md`](releasing.md).

Items are grouped by area. None of them block normal operation of
the 1.0.x line — they're known gaps or refinements.

## Group propagation overhaul

The 1.0.0 plumbing for group membership changes is correct but
inefficient at scale, and federated-from-LDAP groups don't propagate
at all. These two are the same problem space — a coherent solution
touches both.

- **Incremental PATCH delta.** _Done._ `GROUP_MEMBERSHIP` events now
  dispatch `ScimClient.patchGroupMembership`, which (when
  `group-patchOp=true`) sends a single-member ADD/REMOVE PATCH instead
  of re-sending the full member list — a user joining a 10k-member group
  produces a one-member request. REMOVE uses the RFC 7644 filter path
  `members[value eq "..."]`. `group-patchOp=false` deployments fall back
  to the existing full `replace`. Verified by `GroupMembershipPatchTest`
  (wire shape) and `ScimGroupPropagationIT` (end-to-end add/remove).
  Note: this covers membership *changes*; a full group `replace` (name
  edits, sync-refresh) still sends the whole list via `toPatchBuilder`.
- **LDAP-federated group membership.** _Done._ Federated users'
  current group memberships now propagate to SCIM via
  `ScimLdapStorageMapper.onImportUserFromLDAP` →
  `ScimClient.ensureGroupMembership`: for each group the imported
  user belongs to, the mapper first ensures the SCIM group exists
  (idempotent create; skipped when `group-patchOp=false` because the
  `replace` fallback already covers it), then adds the member via a
  single-member delta PATCH. Additions only; membership is
  re-asserted on every import. Requires the SCIM provider component
  to enable both `propagation-user=true` and `propagation-group=true`
  — membership resolution looks up the user's SCIM mapping under the
  same component id, so a group-only component cannot resolve
  members. Verified by `EnsureGroupMembershipTest` (unit) and
  `ScimLdapGroupMembershipIT` (integration). Membership REMOVAL
  (user dropped from an LDAP group) is not yet handled; it is a
  deferred reconciler-style follow-up. Group rename and delete for
  federated groups are also not yet handled.

## Auth-mode follow-ups

The OAuth 2.0 `CLIENT_CREDENTIALS` mode shipped in 1.0.0 deliberately
deferred the following. Each was considered and deferred until there's
a concrete IdP that requires it.

- **OIDC discovery** (`.well-known/openid-configuration`) — operator
  supplies the token endpoint URL directly today.
- **`client_secret_post`** client authentication — only
  `client_secret_basic` is supported.
- **`private_key_jwt` / mTLS bearer** (RFC 8705).
- **`audience` request parameter** — Keycloak doesn't honor it on the
  client_credentials request body anyway; configure via a token mapper
  on the client.
- **Proactive refresh-ahead-of-expiry** — lazy refresh with 30s skew is
  in place; proactive would burn a thread for ~1–2% throughput at the
  expiry boundary.

## Reconciler refinements

- **Bloom-filter witness.** Designed in
  [`docs/ldap-federation-support.md`](ldap-federation-support.md) but
  not implemented. Belt-and-suspenders against silent
  timestamp-write failures.
- **Phase 1 parallelization.** At 10k mappings the sequential mapping
  walk + `getUserById` takes ~10s. Only matters at extreme
  reconciliation volumes; typical "delete a few hundred stale users"
  doesn't approach that.

## Resilience

- **SCIM-endpoint 5xx/429 retry.** _Done._ The SCIM SDK returns 5xx as
  `ServerResponse` with `isSuccess()=false` rather than throwing, so
  resilience4j's exception-based retry didn't fire. Now `ScimClient`'s
  `RetryConfig` adds a `retryOnResult(...)` predicate
  (`isRetryableStatus`: 429 + any 5xx) covering create/replace/delete.
  Verified by
  `ScimResilienceIT#serverErrorIsRetriedAndEventuallySucceeds` and
  `ScimClientRetryTest`.
- **Token-endpoint 5xx/429 retry.** _Done._ `HttpTokenMinter` mints
  tokens outside the SCIM retry path and threw a bare `RuntimeException`
  on any failure. Now `OAuthClientCredentialsTokenSource` wraps the mint
  in a resilience4j `Retry` (`maxAttempts(3)`, exponential backoff) that
  retries transient failures — transport faults + 429 + any 5xx, via
  `isRetryableMintFailure` reusing `ScimClient.isRetryableStatus` — and
  never 4xx config errors. The smaller budget reflects that mints run
  under the per-component lock. Verified by
  `ScimOidcAuthIT#tokenEndpointTransientErrorIsRetried` and
  `OAuthClientCredentialsTokenSourceRetryTest`.

## SCIM protocol features

- **SCIM `/Bulk` batching.** Not implemented. Today every resource
  change produces an individual HTTP request. Bulk would let a full
  sync collapse N requests into one.

## Performance / observability

- **Redundant per-sync membership re-assertions.** On a full sync,
  `onImportUserFromLDAP` fires multiple times per user (Keycloak
  calls it for each mapper in the chain, and again on each sync
  pass), so each invocation re-asserts all of that user's group
  memberships. The operations are idempotent (op=add on an existing
  member is a no-op at the SCIM level), but the call volume grows
  linearly with users × groups × invocations-per-user-per-sync. The
  existing user-propagation path has the same multiplication
  characteristic. A potential optimization: deduplicate within a sync
  window (e.g. a per-session seen-set) so each membership is
  asserted at most once per sync run.
- **Perf-rig sibling container.** The Testcontainers + Keycloak +
  WireMock setup routes SCIM traffic through an SSH tunnel
  (`host.testcontainers.internal`), adding ~25–30 ms per request to
  the `ScimClientMetrics` numbers in
  [`docs/performance.md`](performance.md). Moving WireMock to a sibling
  container on Keycloak's Docker network would make the published
  numbers reflect real network cost. Doesn't affect production.

## Test gaps (1.x scope)

Coverage that didn't make 1.0.0's bar but is reasonable to add:

- Persistence across Keycloak restart
- Concurrent admin operations against the same component
- LDAP-side auth failures
- TLS certificate validation paths
- Long usernames / special-character payloads

## Code quality

- **Split `ScimClient` further.** The auth/header concern was extracted
  into `ScimAuthHeaders` in 1.0.2 ([#25]); the remaining bulk still
  splits naturally along create/replace/delete + retry/failure-handling.
  Not blocking, but the file is still large.

  _Done in 1.0.2:_ adapter instantiation no longer uses reflection —
  `getAdapter(Class)` was replaced by the `AdapterFactory` functional
  interface invoked via constructor references ([#25]).

## Documentation

- **`CONTRIBUTING.md`** — code-style notes, branch-naming conventions,
  TDD expectations, commit-message format.

[#25]: https://github.com/pelotech/keycloak-scim/issues/25

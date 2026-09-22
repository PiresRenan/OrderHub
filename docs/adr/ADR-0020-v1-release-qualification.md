# ADR-0020 — v1 release qualification and client trust

Status: DESIGNED — final release gates remain pending.

Task: OH-022, [Issue #41](https://github.com/PiresRenan/OrderHub/issues/41).

## Evidence and scope

Discovery started from integrated OH-021 `d3aa23a42e949cd2299342f414476d2028ef69a0`.
The initial Wrapper clean verify passed 1,541 tests without failures/errors/skips;
the JavaScript gate passed 320 generated string cases. Source and generated
OpenAPI agree on 60 operations across six business controllers. All 42 accepted
V1–V44 migration hashes match; B44 remains immutable.

Those tests did not establish direct cross-origin frontend support. Real HTTP
preflights on Orders and both identity-bootstrap paths returned 401 without
origin admission. Signed-token/filter regressions also demonstrated acceptance
of absent expiry and, under matching issuer/audience, ID-token purpose. These
are bounded transport/authentication corrections, not a new product phase.

## Decision

Use Spring CORS processing on the existing business and bootstrap chains with an
empty-by-default exact-origin allowlist. Admit only explicit HTTPS origins or
literal HTTP loopback development origins, fixed API methods/headers and no
cookie credentials. Expose Location, bearer challenge and Retry-After headers.
CORS does not grant identity or domain authority and does not expose management
or documentation endpoints. Rejected CORS traffic can have a framework transport
response rather than an application Problem Detail.

Require expiry through Spring's timestamp validator for every provider, retaining
its clock skew and optional not-before behavior. Nimbus continues to verify
signatures against operator-configured keys. Add an explicit per-issuer
`GENERIC`/`COGNITO` profile; Cognito requires access-token purpose and an HTTPS
resource audience plus a string `client_id` in that issuer's explicit
`allowed-client-ids` (empty list fails startup). `aud` identifies the Resource
Server and `client_id` the admitted App Client; neither is business authority.
The profile must be configured explicitly for every issuer; omission fails
startup rather than defaulting to `GENERIC`. No app-client-ID audience fallback
or claim-to-business-role mapping is introduced. Existing generic providers and mixed migration remain
supported. These changes were preceded by failing signed/filter regressions.

Browser/mobile human clients use Cognito code+PKCE and resource-bound access
tokens. A BFF is an optional client custody decision. Independent service
principals/client-credentials, payment adapters, AI/ML, general Customer signup
and a full Workforce backoffice HTTP API remain POST-v1. Existing controlled
User/CustomerProfile/Platform provisioning is a prerequisite, not an invisible
public endpoint. The continuous journey explicitly conditions only its owned
test fixture, then exercises admitted APIs and verifies durable effects.

No domain, database, broker, cache, migration or process-local locking change is
needed for these corrections. Stable package/OpenAPI/image metadata is 1.0.0.

A controlled connection-pool outage found another boundary defect: unchecked
identity lookup failures escaped the bearer filter, logged the persistence
cause and ended as a misleading 401 on error dispatch. A signed-filter RED
reproduced that escape. The converter now maps only the typed identity-persistence
failure to sanitized OAuth temporary unavailability; the entry point emits 503
with no-store and `Retry-After: 1` as a backoff floor. An unexpected
identity-resolution defect is a sanitized 500 without retry advice. Missing bindings remain 401. No
business effect is admitted while identity cannot be established.

## Qualification and consequences

Run `ReleaseSecurityBoundaryTest`, `ReleaseSecurityEnvironmentTest` and
`ReleaseQualificationJourneyTest`, then complete Wrapper clean verify, Modulith,
migration, OpenAPI/JavaScript, image, Compose and Kubernetes gates. The release
experiment in `scripts/qualification/OperationalProbe.java` reports all repeated
stages and recovery observations; its co-located tiny-dataset results are not
production capacity promises. Actual Cognito account/TLS/ingress and database
operations require deployment-specific qualification.

ADR-0003 remains authoritative: task squash into pre-release, qualified
pre-release PR into main using a merge commit, exact tree verification, then
immutable `v1.0.0`. An unqualified candidate, missing review or incomplete
mandatory check cannot be represented as GO. Final evidence and completion
status are recorded only after those gates run.

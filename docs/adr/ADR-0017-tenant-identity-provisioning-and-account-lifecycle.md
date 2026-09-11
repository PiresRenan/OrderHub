# ADR-0017 — Tenant Identity Provisioning and Account Lifecycle

Status: TESTED

## Context

OrderHub has deliberately evolved identity in layers rather than treating authentication, membership, persona and authorization as one account object.

The integrated baseline for this decision is:

`pre-release@9e87d15e98db88f95f5829f6e8f57a4aadfb4b80`

with tree:

`d26e99e228cab3e7ccc3d5a1fa804fa6c6f364cf`.

At this baseline:

- `users.User` is the stable provider-neutral internal identity;
- `users.ExternalIdentityBinding` maps exact external `(issuer, subject)` identity to one internal User;
- `users.TenantMembership` represents only the `(userId, tenantId)` association and intentionally carries no roles, permissions or authentication semantics;
- `security` validates external bearer identity and derives trusted actor/Tenant context from authoritative internal facts;
- `workforce` owns StaffProfile, employment/position state and Staff authorization ceilings;
- `customers` owns CustomerProfile and Customer↔User commercial/account binding;
- `authorization` owns roles, permissions, assignments, overrides and decisions;
- `tenants` owns Tenant operational state;
- Platform/Organization administration and Tenant business administration are already established without collapsing upper-scope authority into Tenant-private authority.

These boundaries solve authentication and authorization after relationships exist, but they do not yet provide a complete operational lifecycle for establishing, changing and terminating those relationships.

A production-realistic system must be able to onboard Staff, safely link a pre-existing Customer relationship to an authenticated account, migrate or revoke external provider identities, and terminate Tenant access without direct persistence manipulation or insecure claim-by-identifier workflows.

OH-019 / issue #38 is therefore the last planned v1 business-security capability before operational housekeeping, API/runtime hardening and final release qualification.

## Problem

The missing lifecycle creates four concrete classes of risk.

### 1. Provisioning gap

Staff/workforce models are durable and authorization-aware, but a new Staff relationship still needs a governed path that establishes the required identity/membership/workforce state without letting an untrusted caller self-provision or exceed the provisioning actor's delegation ceiling.

### 2. Account-claiming gap

OH-015 intentionally allowed CustomerProfile to exist before an authenticated User binding and explicitly deferred the external proof/bootstrap mechanism. A Customer must not be claimable merely by knowing a UUID, email-like value or provider claim.

### 3. External-identity lifecycle gap

External identity binding currently represents the durable authentication mapping but not operational link/unlink/relink/provider-migration workflows. A stale or concurrent relink must not move one provider identity between Users or silently orphan authentication state.

### 4. Membership termination gap

TenantMembership currently represents association only. The system needs an explicit way to make suspension/termination affect future trust establishment while retaining historical domain/audit references and keeping roles/personas separate from membership existence.

## Decision standard

OH-019 continues the repository governance rule:

`problem -> evidence/hypothesis -> decision -> alternatives -> impact -> executable validation`

Implementation follows mandatory TDD:

`RED -> minimal GREEN -> inspect/refactor -> targeted regression -> broader regression -> hostile review -> clean checkpoint`.

This ADR records the decisions established through runtime/schema RED and
adversarial integration evidence, with qualification history in the
[execution ledger](../oh019-execution-evidence.md).

## Decision

### Identity remains layered

The following concepts remain distinct:

```text
external authentication identity
!= internal User
!= TenantMembership
!= StaffProfile
!= CustomerProfile
!= persona
!= role / permission
!= Platform / Organization authority
```

Provisioning coordinates owner contracts; it does not merge those concepts into a generalized Account aggregate.

`User` remains stable across provider migration.

Provider issuer/subject values never become authorization roles or Tenant authority.

### Module ownership remains authoritative

- `users` owns User, TenantMembership and ExternalIdentityBinding lifecycle state;
- `workforce` owns StaffProfile and organizational/authority lifecycle;
- `customers` owns CustomerProfile and Customer↔User account relationship;
- `authorization` owns business/administrative permission decisions and delegation constraints;
- `security` consumes authoritative identity plus a narrow Users-owned operational-membership predicate to establish trust, and does not consume membership state or become provisioning storage;
- `tenants` owns Tenant operational lifecycle;
- HTTP adapters remain thin and do not own cross-module policy.

Cross-module integration uses narrow named/application contracts and opaque identifiers.

No module may query another module's tables or import foreign persistence adapters as a provisioning shortcut.

### Staff provisioning is privileged orchestration

A Staff onboarding flow must prove the provisioning actor has sufficient current authority before sensitive target lookup or mutation.

Provisioning must not grant permissions merely because a User or membership was created. Workforce position/authority and role assignment remain governed by their existing ceilings and delegation rules.

The Workforce intent freezes Tenant, issuer, department, position, optional
initial role, operation fingerprint, correlation and expiry. It stores only a
SHA-256 digest of 256 random bits. New issuance returns its credential once;
matching replay returns only the intent ID, and changed frozen facts conflict.
Staff TTL is required configuration, currently 30 minutes.

Consumption uses one conditional UPDATE RETURNING, revalidates issuer authority,
resolves or creates the external User under the exact-pair PostgreSQL advisory
lock, ensures active membership, materializes Staff and optionally assigns the
initial role. One REQUIRED transaction owns all state and owner-local evidence.
Any downstream failure rolls back proof, speculative User, binding, membership,
Staff and role effects. A row trigger checks proof expiry again after lock wait.
Existing inconsistent Staff placement is a conflict, never an implicit overwrite.

Normal issuance requires TENANT_MEMBERS_MANAGE. Optional role assignment also
requires TENANT_ROLES_ASSIGN and RoleDelegationPolicy; protected/governance roles
require the independent privileged-role permission. Over-broad placement or
role envelopes are rejected rather than clipped. Current owner authority facts
are stabilized through the mutation transaction.

The explicit first-Staff ceremony requires PLATFORM_TENANTS_MANAGE and the
existing Tenant governance lock. Any historical Staff, including inactive
Staff, or a completed ceremony closes cold start. It establishes the exact
INITIAL_GOVERNANCE_V1 placement and INITIAL_TENANT_GOVERNANCE_V1 custom role with
an explicit 17-permission ceiling. Normal provisioning never falls back to cold
start. Platform cleanup ends once Tenant Staff authority exists.

Email/SMS delivery is not part of identity correctness and is not introduced without a concrete integration requirement.

### Customer account linking requires explicit proof

An existing CustomerProfile may be linked to a trusted internal User only through a dedicated proof workflow.

Knowledge of Customer UUID, email-like data, JWT subject text or arbitrary provider claims is not account ownership proof.

The final proof mechanism must be the smallest one that can be securely tested. Customer linking never grants Staff authority.

The implemented Customer contract uses a 256-bit one-time capability issued by
current workforce-bounded `TENANT_MEMBERS_MANAGE` authority inside an active
Tenant. Only its SHA-256 digest is stored. The 15-minute proof selects the
Customer at consumption; a second caller-selected Customer ID is not accepted.
The authenticated internal User, ensure-active membership, exact Customer binding
and required evidence participate in one transaction. Suspended or terminated
memberships are not reactivated. Independent authorized proofs preserve the
existing exact-tuple cardinality and do not remove previous account bindings.
Issuance replay returns only the proof identifier; losing the original response
requires cancellation and a new operation. HTTP/JWT adapter qualification remains
part of the final OH-019 gate, not implied by this application contract.

### External identity lifecycle preserves stable internal identity

The system must support the operational semantics admitted by OH-019 for linking an additional external identity, unlinking, relinking/provider migration and recovery while preserving one stable internal User identity.

The persistent uniqueness of `(issuer, subject)` remains a correctness boundary.

An external identity must not be reassigned to another User through stale writes, duplicate requests or races.

Unlink/recovery rules must explicitly address the risk of leaving a User with no usable authentication path instead of treating deletion as a generic CRUD operation.

The implementation in progress uses add-then-remove migration. A currently
authenticated User obtains a one-time 256-bit, digest-only, 15-minute proof. A
separately verified external identity consumes it and is linked to that same
User. Existing ownership by another User denies and rolls back consumption.
An unlinked binding retains its exact pair, internal owner and opaque binding
identifier with `active = false`; it is not freed for reassignment. A new proof
and renewed verification may reactivate only that original owner's binding.
Ordinary first-sighting provisioning cannot reactivate a revoked binding or
commit a replacement User for it.

Link and unlink serialize on the existing User row with `FOR NO KEY UPDATE`.
Link additionally joins the published exact-pair advisory scope, in pair-before-
User order. The weaker User row lock remains compatible with binding INSERT
foreign-key checks. Unlink denies removal when no other locally active binding
has an issuer in the current server trust configuration. This does not claim to
detect account suspension inside an external provider; provider availability and
upstream account administration remain external facts. No last-path removal or
privileged orphan-account recovery API is introduced.

Security owns the issuer allowlist and supplies its boolean contract to Users.
The primary issuer/JWK/audience properties retain their behavior. Optional
`orderhub.security.jwt.additional-issuers[n].issuer` and
`orderhub.security.jwt.additional-issuers[n].jwk-set-uri` allow an explicitly
configured migration overlap. Each configured decoder applies the same
`JwtValidationPolicy` and audience. The unverified issuer only selects a decoder
from this fixed map; it never selects a network endpoint or establishes identity.
Unknown issuers fail before key retrieval. No issuer discovery is added.

Authentication resolution reads only active bindings. Its PostgreSQL statement
snapshot is the boundary: resolution while unlink is uncommitted can still
succeed, but a subsequent ordinary resolution after commit rejects the old pair.
Already-started requests are not retroactively cancelled. Lifecycle transitions
and append-only evidence share the caller's transaction, without storing issuer,
subject, JWT or linking credentials in audit evidence.

### Membership lifecycle affects trust, not history

TenantMembership uses the published ACTIVE, SUSPENDED and TERMINATED states.

The operational administration contract explicitly supports ACTIVE -> SUSPENDED,
SUSPENDED -> ACTIVE recovery, and ACTIVE/SUSPENDED -> TERMINATED. Requesting the
already desired state returns unchanged; TERMINATED cannot recover. The existing
ensure-active provisioning contract never performs these transitions implicitly.

Workforce coordinates administration using the trusted internal actor, an ACTIVE
Tenant and actor membership, current TENANT_MEMBERS_MANAGE authorization, and
the current Staff ceiling. A Staff target cannot exceed the actor's band or
position envelope. Self transitions are denied. Customer-only membership does
not require fabricating a Staff target. No role assignment is changed.

The existing Workforce Tenant governance lock serializes these administrative
transitions. Authority is re-evaluated after lock acquisition, so two governance
actors cannot both disable one another. Workforce facts and role policy are read
through the existing coherent authorization snapshot; no additional Staff row
locks invert provisioning's lock order. This is point-in-time authorization,
not a promise to cancel work after a later permission or placement change.
The ambient transaction also checks actor operational state: the isolated read
snapshot must not hide an actor termination already written by that caller.

Users performs the locked membership transition and V43 append-only evidence
in the coordinator's same REQUIRED physical transaction. Evidence records only
internal attribution, bounded before/after states, action and correlation.
Audit failure rolls back the transition; no independent audit commit occurs.

The visibility boundary for future trusted context is the transition commit:
another connection can still observe ACTIVE while suspension is uncommitted;
new context establishment after that commit denies access. Previously established
contexts and in-flight requests are not retroactively cancelled.

A non-active membership must be ineligible for new `TrustedTenantContext` establishment.

Membership suspension/termination does not silently delete StaffProfile, CustomerProfile, Orders, privileged audit evidence or other historical state.

Roles/permissions remain authorization state; they do not define whether membership exists or is operationally active.

These transition and recovery rules follow the runtime/schema RED and
PostgreSQL concurrency evidence, without changing ensure-active semantics.

### Authorization before sensitive lookup

Privileged provisioning and linking operations follow:

```text
trusted actor
-> required authorization / delegation decision
-> sensitive target resolution
-> mutation
```

when earlier target resolution could create an enumeration oracle.

Unauthorized callers cannot distinguish sensitive target existence through response shape, status or detail.

Technical uncertainty fails closed but is not misrepresented as policy denial.

### Secrets and bootstrap credentials are minimized

Staff, Customer and external-link proofs use cryptographically unguessable
one-time credentials and return them only on initial issuance.

Raw values must not be persisted when a one-way digest is sufficient and must never appear in logs, metrics, audit payloads, exception messages or Problem Details.

One-time credentials require concurrency-safe single consumption.

No password store, token issuer or OAuth/OIDC authorization server is introduced.

### Owner-local transactional evidence

Privileged identity/provisioning mutations that require auditability use the established owner-local pattern:

```text
authoritative mutation
+ bounded append-oriented evidence
= same transaction
```

Audit persistence does not use an autonomous `REQUIRES_NEW` transaction.

When atomic accountability is required, evidence failure aborts the authoritative mutation.

No global generic audit platform is introduced merely to eliminate repeated structure.

### Retry semantics are operation-specific

Desired-state commands should be naturally idempotent where possible.

One-time or ambiguous-outcome commands must define durable replay/duplicate semantics when a retry could repeat a privileged effect.

The Orders and Inventory idempotency designs are precedents, not libraries to copy mechanically.

The minimum safe identity for retry coordination is selected per concrete workflow from RED evidence.

### PostgreSQL is the multi-instance correctness boundary

Correctness for uniqueness, one-time consumption, lifecycle transition and competing identity/link operations is enforced through PostgreSQL transactions, constraints, conditional mutation and row locking only where evidence demonstrates the need.

JVM-local locks are not correctness boundaries.

Locking is not added to ordinary reads without a concrete race/property that requires it.

### Public failure semantics remain privacy-safe

HTTP errors use stable RFC 9457-style Problem Details.

Framework-derived HTTP statuses retain their semantic status.

Public errors do not reveal raw external subject identifiers, invitation secrets, membership internals, SQL, exception text, stack traces, bearer credentials or authorization internals.

Observability uses low-cardinality operation/outcome/reason dimensions; User/Tenant/Customer/Staff/invitation/external-subject identifiers are not metric labels.

## Executable decision checkpoint — TenantMembership lifecycle foundation

The first OH-019 PostgreSQL/Flyway RED proved that accepted V35 cannot represent
a membership that remains historically present while becoming ineligible for new
Tenant trust: `users.tenant_memberships` contained only `(user_id, tenant_id)`.

That evidence authorizes V36 and freezes only the minimum lifecycle foundation:

- lifecycle state belongs on the existing TenantMembership relationship rather
  than a second aggregate because the proven requirement is operational state of
  that exact relationship;
- the persisted vocabulary is `ACTIVE`, `SUSPENDED`, `TERMINATED`;
- newly established and pre-V36 memberships are `ACTIVE`;
- only `ACTIVE` membership is eligible to participate in new
  `TrustedTenantContext` establishment;
- `SUSPENDED` and `TERMINATED` preserve relationship/history while denying new
  trust;
- roles, permissions, StaffProfile, CustomerProfile and Tenant operational state
  remain separate;
- V36 adds only the bounded status state and PostgreSQL check constraint; it does
  not add timestamps, versions, invitation state or authorization data;
- this checkpoint does not yet admit transition/recovery semantics. Whether
  suspension may recover, whether termination is irreversible and how concurrent
  lifecycle mutations are serialized require their own executable transition RED.

The original schema RED turned GREEN, and its temporary test name has been
replaced by permanent V35 -> V36 migration, persistence and Security
regressions.

## Executable decision checkpoint — Users operational-membership boundary

Spring Modulith proved that calling `TenantMembership.isOperationallyActive()`
from `security` crossed a non-exposed `users` domain boundary. That evidence
authorizes the following boundary and changes no trust rule:

- `users` owns membership lifecycle interpretation, including which states
  remain operational;
- `security` consumes a narrow Users-owned operational-membership eligibility
  capability rather than membership state;
- that capability accepts only an internal `userId` and a `tenantId`, and
  returns no `TenantMembership` or other Users domain type;
- `FindTenantMembershipUseCase`, whose contract returned the domain model, was
  retired from this cross-module path;
- fail-closed trust semantics are unchanged: an absent membership, a
  non-operational membership, an unknown Tenant and a non-`ACTIVE` Tenant each
  deny trusted Tenant context, and Tenant state is still not probed once
  membership has already denied.

## Executable decision checkpoint — Users exposed application API surface

`users.application.port.in` is the framework-neutral cross-module application
boundary; Users domain models remain internal to the module. An executable
architecture rule over that package proved three legacy return-type leaks that
predated the membership checkpoint, so the exposed contracts were corrected
rather than the rule that guards them:

- `CreateUser` exposes only an application-owned internal identity result,
  because the identifier is generated inside Users and is genuinely new
  information a caller cannot otherwise obtain;
- membership establishment and external identity binding return no aggregate,
  because their commands already carry every caller-known identity and
  persistence contributes nothing further;
- this prevents a future cross-module consumer from acquiring a hidden
  `users.domain` dependency merely by calling an exposed Users contract;
- Spring Modulith validates dependencies a consumer actually creates, while the
  `users::api` rule guards the exposed surface before any consumer exists; the
  two are complementary and neither replaces the other.

Duplicate and persistence failure types still reside in the non-exposed Users
output-port package. They appear in no exposed signature and no external
consumer depends on them, so their public failure contract stays deferred until
a provisioning orchestrator proves what it needs.

## Executable HTTP decision checkpoint

### Current HTTP implementation checkpoint

The [HTTP contract](../oh019-http-contract.md) records the implemented thin
Administration routes. Two bootstrap paths use the same configured Resource
Server decoder as ordinary authentication and project only verified exact
issuer/subject with zero granted authorities. A valid JWT alone creates no
User; the one-time business proof selects the authorized relationship. Normal
routes still require an active internal binding. Customer linking consumes
proof as an already bound User and adds no generic registration or delivery
infrastructure.

Strict JSON binding rejects supplied identity/authority fields. Declared JSON
response negotiation prevents unsupported Accept representations from spending
a proof before returning 406. Authentication failures have sanitized bootstrap
Problem Details; malformed input is 400, policy rejection 403, authorized state
conflict 409, and technical uncertainty 500. Lifecycle error instance paths do
not reflect internal selectors. Secret-bearing request/result toString output
is redacted and successful responses disable caching.

The real production-decoder acceptance suite uses two independent RSA/JWK
providers and real PostgreSQL. Nineteen HTTP/JWT cases and four modularity cases
passed. Final canonical `mvnw.cmd -B clean verify` passed **1500 tests, zero
failures, zero errors and zero skipped**, including the four modularity tests,
on 2026-09-11 at 04:33:46 -03:00 in 8:14. The
[execution ledger](../oh019-execution-evidence.md) preserves the preceding
infrastructure interruption and isolated composition-fixture correction.
Remote CI and review remain separate approval gates; no interrupted run is
counted as green.

The original design questions are resolved by the implemented decisions:
bounded owner proofs, add-then-remove migration, last-path denial, explicit
suspension recovery, owner-coordinated REQUIRED transactions, existing
permissions, fingerprint replay and desired-state idempotency. No generic IAM,
registration, delivery or privileged orphan-recovery system is introduced.

## Concurrency validation model

Where the final design admits the operation, executable PostgreSQL tests must cover the invariants exposed by at least these competing mutations:

- invite/provisioning consume vs consume;
- consume vs cancellation/revocation;
- Customer link vs competing Customer link;
- external identity link vs link of the same `(issuer, subject)` to another User;
- unlink vs authentication resolution/provider migration;
- membership suspension/termination vs trusted-context establishment;
- recovery/reactivation vs competing termination;
- provisioning vs concurrent authority/delegation change where that race can permit privilege beyond the actor's current ceiling.

Tests should synchronize the relevant race window deterministically instead of depending on arbitrary sleeps.

## Persistence and migration governance

Published V1-V43 migrations are immutable. OH-019 adds V36-V43: membership
state, Staff intent, owner evidence, authority stabilization, post-lock deadline,
Customer proof, external identity lifecycle and membership evidence. Upgrade
tests preserve predecessor data and checksums. No cross-module foreign key is
introduced by these changes.

The next integer is not itself authorization for a migration.

New schema state may be introduced only after executable RED demonstrates that the current schema cannot represent a required invariant.

After an OH-019 migration is published, correction is forward-only.

Final migration acceptance includes empty DB -> latest, relevant accepted predecessor -> latest and accepted checksum/integrity verification.

## Security / privacy threat model

OH-019 specifically guards against:

- account takeover through Customer/profile identifier knowledge;
- external-subject reassignment;
- forged or replayed invitation/linking credentials;
- duplicate one-time credential consumption;
- cross-Tenant provisioning;
- Customer/Staff persona leakage;
- authorization bypass through JWT/provider claims;
- self-escalation during Staff onboarding;
- enumeration of User/Customer/membership/external-identity existence;
- stale provisioning after actor authority changes where relevant;
- secrets or linkable provider identifiers leaking through logs/errors/metrics;
- historical/audit destruction during membership or identity termination.

## Alternatives rejected at this stage

### Build an OrderHub identity provider

Rejected. Authentication is already delegated to external OAuth2/OIDC-compatible identity and OrderHub is a resource server. Password/token issuance would expand the trust boundary without a business requirement.

### Put provisioning state into Security

Rejected. Security is an authentication/trust adapter, while Users/Workforce/Customers own durable business relationships.

### Merge User, Staff and Customer into one Account aggregate

Rejected. It would violate established persona/module boundaries and create authority leakage.

### Trust email, JWT claims or Customer UUID as linking proof

Rejected. These values are selectors/assertions, not durable ownership proof.

### Add a generic IAM / ReBAC product

Rejected absent requirements that exceed the existing authorization kernel.

### Use JVM locks for invitation/link races

Rejected because correctness must hold across multiple application instances.

### Audit with `REQUIRES_NEW`

Rejected because it can persist misleading evidence for a business mutation that rolls back.

## Explicitly deferred

OH-019 does not implement:

- password storage;
- token issuance / OrderHub authorization server;
- external IdP administration console;
- email/SMS delivery;
- OrderHub-owned MFA;
- SCIM;
- generic enterprise SSO provisioning;
- generic approval/workflow system;
- generic IAM/ReBAC platform;
- JIT privileged access unrelated to concrete provisioning needs;
- OH-020 housekeeping;
- OH-021 broad OpenAPI/runtime hardening;
- OH-022 release qualification/promotion;
- post-v1 commerce expansion.

## Validation / promotion rule

ADR-0017 remains `DESIGNED` until the final local implementation has executable
evidence for the admitted lifecycle, retry, concurrency, audit, anti-enumeration,
cross-Tenant and migration contracts, full Maven Wrapper `clean verify`, and
Spring Modulith verification. It can then be marked `TESTED` for the qualified
implementation before opening the governed PR.

PR approval is a separate gate: exact-HEAD Branch Policy/CI/Platform Validation
must pass, and both comprehensive and security-focused Codex reviews must be
clean on that same HEAD with no unresolved material findings. Local `TESTED`
status never substitutes for those remote gates or human release approval.

The governed PR targets only `pre-release` and uses squash integration under ADR-0003.

After integration, parent/tree identity and issue completion are verified before feature-branch cleanup.

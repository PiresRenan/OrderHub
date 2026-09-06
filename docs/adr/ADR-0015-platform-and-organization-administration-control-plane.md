# ADR-0015 — Platform and Organization Administration Control Plane

Status: DESIGNED

## Context

ADR-0011 established internal User identity, explicit authorization personas,
deny-by-default authorization and a system-owned permission vocabulary.

ADR-0012 established Tenant workforce authority as business state distinct from
User identity, TenantMembership and RoleAssignment.

ADR-0013 established Customer identity and self-service relationships without
turning Customers into Staff roles.

ADR-0014 established privacy-safe operational analytics and is integrated in the
current OH-017 baseline.

OH-017 introduces the next administrative hierarchy:

```text
PLATFORM
  -> ORGANIZATION
      -> TENANT
          -> RESOURCE
```

The hierarchy represents organizational placement and administrative
jurisdiction. It does not create implicit permission inheritance over
Tenant-private business data.

The enduring separation is:

```text
authenticated User
!= authorization persona/capacity
!= administrative scope
!= administrative grant
!= TenantMembership
!= StaffProfile
!= Tenant RoleAssignment
!= Organization/Tenant placement
!= resource authorization
```

The implementation baseline for this decision is:

```text
pre-release@d4beb38d0d03f0b016e12ff7766c2117d0fad42d
```

That baseline contains completed OH-016 / ADR-0014 and accepted Flyway
migrations through V23.

## Decision

Introduce `organizations` as the business module that owns Organization identity,
Organization lifecycle and Organization/Tenant placement.

Introduce `administration` later as a thin control-plane adapter only when an
executable HTTP use case requires it. It does not own Organization, Tenant,
identity, grant or authorization persistence.

Keep:

- `users` authoritative for internal User identity and TenantMembership;
- `tenants` authoritative for Tenant identity and operational lifecycle;
- `authorization` authoritative for permission vocabulary, compatibility,
   grants and authorization decisions;
- `security` responsible for authenticated-principal adaptation and trusted
   request context;
- Tenant business modules authoritative for their private data plane.

No upper administrative scope becomes a Tenant Staff identity merely because it
is higher in the organizational hierarchy.

## Canonical Organization concept

`Organization` is the canonical business grouping above Tenant.

`Network` is not a second aggregate in OH-017. A business network is represented
by Organization semantics unless a later requirement proves a distinct concept.

Organization currently owns:

- immutable internal UUID;
- canonical bounded name;
- `ACTIVE` / `SUSPENDED` lifecycle;
- Organization/Tenant placement.

Organization creation canonicalizes surrounding whitespace.

Persisted/rehydrated Organization state is validated rather than silently
normalized.

Organization name length is bounded to 120 Unicode code points.

## Organization lifecycle

Organization lifecycle is:

```text
ACTIVE
SUSPENDED
```

Suspension:

- preserves Organization identity;
- does not delete the Organization;
- does not delete its Tenant placements;
- does not implicitly mutate Tenant lifecycle;
- does not destroy durable administrative grants.

Recovery returns the Organization to ACTIVE.

HTTP desired-state idempotency belongs to the later application/control-plane
boundary and is not inferred merely from domain methods.

## Tenant lifecycle

OH-017 introduces the requirement for Tenant operational lifecycle:

```text
ACTIVE
SUSPENDED
```

A suspended Tenant remains durable and administratively remediable but is not a
valid Tenant workspace.

Security resolves Tenant operational state exclusively through the Tenants-owned
Spring Modulith Named Interface:

```text
tenants::operational
```

That boundary exposes only a bounded query, operational-state vocabulary and a
sanitized technical-unavailability failure. It does not expose `Tenant`,
`TenantStatus`, Tenant repositories, JDBC or PostgreSQL types.

Trusted Tenant resolution is deliberately ordered:

```text
authenticated User
-> exact TenantMembership
-> Tenant operational-state lookup
-> ACTIVE only
-> TrustedTenantContext
```

Missing membership short-circuits before Tenant-state lookup. Missing or
SUSPENDED Tenant state creates no trusted Tenant context.

Technical failure to establish Tenant operational state is technical uncertainty,
not a false policy denial:

```text
fail closed
-> no trusted Tenant context
-> no protected operation
-> sanitized technical failure
```

## Administrative scope

Administrative scope is distinct from Tenant persona and permission.

The concrete scope vocabulary is now:

```text
PLATFORM
ORGANIZATION(organizationId)
TENANT(tenantId)
```

`PLATFORM` carries no scoped resource UUID. `ORGANIZATION` and `TENANT` require
one immutable opaque UUID.

Scope is only a jurisdiction coordinate. It grants no permission and creates no
hierarchical permission inheritance.

In particular:

```text
PLATFORM grant
!= implicit ORGANIZATION grant
!= implicit TENANT grant
```

## Persona / capacity

The existing Tenant authorization personas remain:

```text
STAFF
CUSTOMER
```

OH-017 deliberately does not add `PLATFORM_ADMIN`, `ORGANIZATION_ADMIN` or
another fake Tenant persona.

`PermissionCode` now has two mutually exclusive compatibility dimensions:

```text
Tenant permission
    -> supported AuthorizationPersona
    -> no administrative scope classification

Administrative permission
    -> supported AdministrativeScopeType
    -> no Tenant persona classification
```

This preserves the distinction between Tenant workforce/customer capacity and
upper control-plane authority. Administrative capability is established by an
authenticated internal User plus an exact durable administrative grant for the
required scope and permission.

`CUSTOMER` permissions therefore cannot become administrative authority, and an
administrative permission cannot masquerade as `STAFF`.

## Administrative grants

Upper-scope authority is represented by an explicit durable relation:

```text
internal User
+ AdministrativeScope
+ bounded PermissionCode
```

V27 persists the relation in:

```text
access_control.administrative_grants
```

The table stores an internal persistence UUID plus:

```text
user_id
scope_type
scope_id
permission_code
created_at
```

Platform scope requires `scope_id IS NULL`; Organization/Tenant scopes require a
non-null opaque scope UUID.

Grant identity is unique across:

```text
user_id
scope_type
scope_id
permission_code
```

using PostgreSQL `UNIQUE NULLS NOT DISTINCT`, so Platform grants retain one
natural idempotency identity even though their scoped UUID is null.

No foreign key reaches into `users`, `organizations` or `tenants`. Cross-module
target existence remains an application-contract responsibility rather than
relational coupling.

PostgreSQL enforces permission/scope compatibility. A Platform permission cannot
be persisted at Organization scope, an Organization permission cannot be
persisted at Platform scope, and Tenant business permissions cannot be stored as
administrative grants.

The grant repository provides concurrency-safe idempotent persistence primitives:

```text
grant
    GRANTED | ALREADY_GRANTED

revoke
    REVOKED | ALREADY_ABSENT
```

These mutation primitives are not yet exposed as privileged control-plane
commands. Grant-management application orchestration remains blocked on the
required same-transaction administrative audit evidence.

Administrative authorization itself is read-only and exact-grant based and
reuses the kernel's existing framework-neutral `AuthorizationDecision` rather
than introducing a second ALLOW/DENY vocabulary:

```text
required AdministrativeGrant exists -> ALLOW
otherwise                           -> DENY
```

No scope hierarchy is traversed. Authorization persistence failure propagates as
technical uncertainty and is never converted into a false policy `DENY`.

The Spring Modulith named boundary for the read decision is:

```text
authorization::administration
```

## Permission vocabulary

The system-owned administrative permission surface is now exactly:

Platform:

```text
PLATFORM_ORGANIZATIONS_VIEW
PLATFORM_ORGANIZATIONS_MANAGE
PLATFORM_TENANTS_MANAGE
PLATFORM_ORGANIZATION_GRANTS_MANAGE
```

Organization:

```text
ORGANIZATION_TENANTS_VIEW
```

No wildcard such as `PLATFORM_ALL` or generic `ORGANIZATION_ADMIN` exists.

V27 evolves `access_control.permissions` without creating a second permission
registry. Exactly one classification is valid for each permission row:

```text
persona != null              + administrative_scope == null
or
persona == null              + administrative_scope != null
```

The existing Tenant role-permission compatibility trigger rejects administrative
permissions because they have no Tenant persona. V27 additionally prevents an
administrative permission from being persisted as a Tenant user permission
override.

Permission persona classification remains immutable, and V27 adds immutable
administrative-scope classification.

## Organization lifecycle persistence and concurrency

Organization lifecycle desired-state persistence reuses the `status` column
introduced by V25. No new schema state is required.

Lifecycle mutation is not implemented by converting aggregate `save()` into an
upsert. Creation/read persistence and lifecycle mutation remain distinct so a
stale aggregate cannot silently overwrite an authoritative lifecycle decision.

The lifecycle persistence port accepts only:

```text
Organization id
+
desired OrganizationStatus
```

and returns a bounded outcome:

```text
UPDATED
ALREADY_DESIRED
NOT_FOUND
```

The PostgreSQL adapter executes inside an adapter-owned Spring
`TransactionTemplate` and locks the Organization row using:

```sql
SELECT status
FROM organizations.organizations
WHERE id = ?
FOR NO KEY UPDATE
```

`FOR NO KEY UPDATE` is deliberately selected instead of the stronger
`FOR UPDATE`: lifecycle changes modify no key used by the Organization placement
foreign key, while this lock is still mutually incompatible with the placement
adapter's `FOR SHARE` lock.

Executable PostgreSQL evidence proves both directions of the lifecycle/placement
race:

- a placement-style `FOR SHARE` lock keeps an Organization ACTIVE/stable through
  the placement transaction and blocks concurrent lifecycle mutation;
- a lifecycle writer lock blocks placement validation until commit, after which
  placement observes the committed SUSPENDED state rather than stale ACTIVE;
- real concurrent suspend/attach execution produces only serializable valid
  outcomes: attach commits while ACTIVE before suspension, or attach is rejected
  after suspension wins.

Suspension does not cascade-detach existing Tenants. Automatic lifecycle
cascades remain explicitly deferred.
## Organization/Tenant placement

A Tenant is:

```text
unassigned
or
assigned to exactly one Organization
```

Placement represents association only.

It does not carry:

- authorization;
- TenantMembership;
- role;
- permission;
- lifecycle;
- business-data ownership.

The single-parent invariant belongs to PostgreSQL when persistence is introduced.

No cross-module foreign key into Tenant-owned persistence is introduced merely
to model the relationship.

## Placement operations

Placement changes are explicit commands:

```text
ATTACH
MOVE
DETACH
```

ATTACH:

```text
unassigned -> attach destination
same destination -> idempotent success
different destination -> conflict
```

MOVE requires:

```text
expected source
+
destination
```

A stale expected source conflicts rather than overwriting newer state.

DETACH requires an expected Organization.

A stale detach must never remove a placement that was concurrently moved.

## Placement persistence and concurrency

PostgreSQL, not a process-local lock, is the correctness boundary.

V26 owns the concrete single-parent relation inside `organizations`:

```text
organizations.tenant_placements
    tenant_id        UUID PRIMARY KEY
    organization_id  UUID NOT NULL
```

`tenant_id` is the structural cardinality key, so one Tenant can have at most one
Organization placement. `organization_id` references only the Organization table
owned by this module. No foreign key reaches into `tenants.tenants`; Tenant
existence/eligibility must be established later through the smallest Tenant-owned
application contract required by the control-plane use case.

Persistence operations are intentionally distinct:

```text
ATTACH
    lock ACTIVE destination
    INSERT ... ON CONFLICT (tenant_id) DO NOTHING
    same destination -> idempotent success
    different destination -> conflict

MOVE
    lock ACTIVE destination
    UPDATE ... WHERE tenant_id = ? AND organization_id = expected_source
    zero rows -> conflict

DETACH
    DELETE ... WHERE tenant_id = ? AND organization_id = expected_source
    no current placement -> idempotent success
    different current placement -> conflict
```

ATTACH and MOVE execute inside an adapter-owned Spring `TransactionTemplate`.
Before mutation they read the destination Organization with PostgreSQL
`SELECT ... FOR SHARE`; this is deliberately narrow row locking intended to keep
the validated destination lifecycle stable until that placement transaction
commits. Spring transaction infrastructure does not leak into the application
port.

Executable PostgreSQL concurrency evidence now proves:

- one-parent Tenant placement;
- concurrent attach to different Organizations has one winner;
- concurrent attach to the same Organization is idempotent;
- concurrent moves from one expected source have one winner;
- stale move rejection;
- stale detach rejection;
- concurrent move versus expected detach cannot remove a completed newer move.

The destination lifecycle/placement race is now closed by the authoritative
lifecycle persistence path and executable PostgreSQL lock-conflict evidence
described above. No JVM-local synchronization participates in correctness.

No generic locking framework is introduced.

## Control plane and Tenant data plane

Platform/Organization administration is a control-plane concern.

Upper-scope authority does not automatically grant access to Tenant-private:

- Orders;
- Customers;
- Inventory;
- workforce records;
- Tenant-private audit;
- future financial/domain resources.

Tenant data-plane authorization remains independently evaluated.

## Trusted context

Existing Tenant trusted context remains Tenant-specific.

`X-Tenant-Id` remains an untrusted selector.

A future Platform route does not manufacture Tenant trusted context merely
because the actor has Platform authority.

A future Organization route does not manufacture Tenant context merely because a
Tenant is placed under that Organization.

## Administrative HTTP boundary

Platform HTTP routes are rooted under:

```text
/platform
```

They do not require `X-Tenant-Id`.

Organization-scoped routes expose only bounded administrative metadata required
by the concrete use case.

Controllers adapt HTTP/authentication state and map bounded Problem Details.

They do not duplicate business authorization policy.

## Authorization ordering and privacy

Platform permission is evaluated before target-sensitive lookup.

An unauthorized actor must not learn whether a supplied Organization, Tenant or
User identifier exists.

Organization-scoped access must preserve privacy-equivalent unavailable behavior
when distinguishing missing, foreign or suspended Organizations would create an
existence oracle.

Infrastructure failure is never translated into a false authorization denial or
continued execution.

## Problem Details

Administrative errors continue the repository's
`application/problem+json` policy.

Public responses must not expose:

- SQL or persistence internals;
- raw exception messages;
- JWT claims or tokens;
- hidden membership/grant state;
- unnecessary personal data;
- hidden Organization existence to unauthorized callers.

## Privileged audit evidence

Every privileged mutation introduced by OH-017 must persist bounded
append-oriented evidence in the same authoritative transaction.

Audit persistence failure aborts the mutation.

No autonomous `REQUIRES_NEW` audit transaction is introduced to preserve
evidence for a mutation that may later roll back.

Evidence excludes credentials, tokens, arbitrary request/response bodies, raw
exception text and unnecessary personal data.

## Observability

Administrative metrics use bounded low-cardinality dimensions such as:

```text
scope
operation
outcome
```

User, Organization, Tenant, grant and correlation identifiers are not metric
labels.

## Module ownership and dependency direction

Module ownership is fixed; exact dependency edges are admitted only when a
concrete application contract requires them.

At the current domain-only checkpoint:

```text
organizations
    allowedDependencies = {}
```

This deliberately prevents future implementation from silently reaching into
authorization, tenants, users, security or analytics.

When an OH-017 use case genuinely requires a cross-module capability:

1. define the smallest owning-module application/named interface;
2. add the consuming edge explicitly;
3. prove it with Spring Modulith verification;
4. do not expose foreign persistence internals.

Future `administration` remains an inbound composition/control-plane adapter. Its
eventual exact edges are not frozen before those application contracts exist.

Authorization must not query Organization/Tenant persistence directly.

Security must not query Tenant persistence directly.

Organizations must not import foreign persistence internals.

## Migration authority

The reconciled OH-017 baseline originally contained accepted migrations through
V23.

V19 and V20 remain intentionally absent and are not reusable merely to make the
sequence contiguous.

V24 is now justified by executable OH-017 evidence: Tenant operational lifecycle
cannot remain an in-memory state because Tenant is already persisted and
rehydrated through PostgreSQL.

V24 therefore establishes the durable Tenant operational-state invariant:

```text
tenants.tenants.status
    NOT NULL
    ACTIVE | SUSPENDED
```

Existing pre-V24 Tenant rows are migrated to `ACTIVE`.

The migration intentionally leaves no column default after upgrade so future

persistence paths must supply aggregate operational state explicitly.

Accepted historical migrations remain immutable.

Flyway out-of-order remains prohibited.

V25 is now justified by executable Organization persistence evidence. The
Organizations module requires durable aggregate storage before lifecycle and
placement application behavior can be implemented without transient state.

V25 therefore creates only the Organization aggregate table:

```text
organizations.organizations
    id      UUID PRIMARY KEY
    name    canonical / bounded
    status  ACTIVE | SUSPENDED
```

The database mirrors the aggregate invariants for canonical non-blank name,
bounded length and operational status. `status` has no implicit default so the
application/repository mapping must provide complete aggregate state explicitly.

Organization/Tenant placement is intentionally not persisted in V25. Its
cardinality, conditional mutation and concurrency strategy require a separate
semantic RED.

V26 is now justified by executable placement persistence evidence and creates
only the Organization-owned Tenant placement relation plus the bounded
Organization lookup index required by later administrative listing.

The migration structurally enforces one parent per Tenant through the
`tenant_id` primary key and deliberately creates no foreign key into Tenant-owned
persistence.

Organization lifecycle desired-state persistence itself required no migration
beyond the V25 lifecycle column and V26 placement relation.

V27 is now justified by executable administrative authorization evidence. The
pre-V27 permission registry required every permission to carry a Tenant persona
and had no durable upper-scope grant relation, so it could not represent OH-017
administrative authority without falsely modeling Platform/Organization
permissions as Tenant `STAFF`.

V27 therefore:

- evolves the single `access_control.permissions` registry so exactly one of
  Tenant persona or administrative scope classifies each permission;
- registers the five bounded administrative permission codes required by OH-017;
- creates `access_control.administrative_grants`;
- enforces administrative permission/scope compatibility in PostgreSQL;
- prevents administrative permissions from entering Tenant permission
  overrides;
- creates no foreign key into User, Organization or Tenant-owned schemas.

The next potential migration number after this checkpoint is V28, but it is not
authorized until another semantic RED proves additional schema state is required.

## Initial implementation evidence


Before this ADR was materialized, OH-017 already established executable domain
evidence for:

- Organization ACTIVE creation;
- required Organization identity;
- canonical mandatory Organization name;
- 120 Unicode-code-point name boundary;
- strict persisted-state rehydration;
- ACTIVE/SUSPENDED Organization lifecycle;
- Organization/Tenant placement identity.

The branch was subsequently reconciled byte-equivalently onto integrated
OH-016:

```text
pre-release:
d4beb38d0d03f0b016e12ff7766c2117d0fad42d
OH-017 reconciled checkpoint:
7aa8259c4baa678fda8b01491804c0fae148b1c8
```

The aggregate OH-017 patch remained byte-equivalent through the rebase.

At that checkpoint:

```text
972 tests
0 failures
0 errors
0 skipped
```

Spring Modulith detected `organizations`, but explicit module dependency metadata
had not yet been established. That missing R07 evidence remains part of this
DESIGNED phase.

## Verification required before TESTED

ADR-0015 remains `DESIGNED` until reviewed executable evidence proves the
complete OH-017 scope.

At minimum that includes:

- Organization domain/lifecycle invariants;
- explicit organizations module boundary;
- Tenant lifecycle;
- PostgreSQL Organization persistence constraints;
- single-parent placement;
- attach/move/detach stale-command semantics;
- placement concurrency;
- destination lifecycle race correctness;
- administrative scope and permission compatibility;
- explicit administrative grants;
- deny-by-default Platform/Organization authorization;
- Tenant-suspension trusted-context denial;
- control-plane anti-enumeration;
- privileged audit atomicity;
- technical failure fail-closed behavior;
- bounded Problem Details/logging/metrics;
- Spring Modulith dependency verification;
- accepted migration immutability;
- `git diff --check`;
- full `mvnw clean verify`;
- required GitHub workflows on the exact implementation checkpoint;
- final review with no unresolved valid finding.

Only after the reviewed executable implementation checkpoint passes those gates
may ADR-0015 be promoted from `DESIGNED` to `TESTED`.

That promotion must be a separate documentation-only checkpoint and receives its
own final workflow/review gates.

## Explicitly deferred

OH-017 does not introduce:

- unrestricted Platform access to Tenant-private business data;
- unrestricted Organization access to Tenant-private business data;
- generalized ReBAC/OpenFGA/Zanzibar infrastructure;
- recursive arbitrary Organization trees;
- multi-parent Tenant placement;
- Organization custom roles without a concrete requirement;
- Organization self-delegation;
- Platform wildcard/super-admin permission for ordinary use;
- normal runtime Platform self-provisioning;
- permanent always-on break-glass bypass;
- generic impersonation;
- automatic lifecycle cascades;
- hard deletion;
- generic distributed locking;
- Redis;
- broker/outbox infrastructure;
- another database or service;
- frontend/admin-console implementation;
- unrelated OpenAPI/UI cleanup;
- speculative resource-level ReBAC.

## References

This decision extends the repository contracts established by:

- ADR-0011 — identity personas and scoped authorization kernel;
- ADR-0012 — Tenant workforce authority lifecycle;
- ADR-0013 — Customer account and self-service;
- ADR-0014 — privacy-safe operational analytics foundation;
- GitHub issue #33 — OH-017 governed scope and acceptance authority.

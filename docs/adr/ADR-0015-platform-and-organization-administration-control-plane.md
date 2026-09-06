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

Administrative scope is distinct from persona and permission.

Initial scope semantics are:

```text
PLATFORM
ORGANIZATION(organizationId)
TENANT(tenantId)
```

Scope expresses where an administrative permission may be exercised.

It does not itself grant permission.

## Persona / capacity

The current authorization kernel contains `STAFF` and `CUSTOMER`.

OH-017 does not add `PLATFORM_ADMIN`, `ORGANIZATION_ADMIN` or another persona
merely to encode a role name.

It also does not yet assert that all Platform/Organization administrative
permissions are necessarily `STAFF` permissions.

The exact compatibility between an authenticated actor capacity and future
upper-scope administrative permissions must be established by executable
authorization evidence before the shared authorization model changes.

Whatever representation is selected must preserve these invariants:

- authentication alone never grants administrative authority;
- upper scope is not represented as a higher Tenant workforce AuthorityBand;
- Platform/Organization authority does not manufacture TenantMembership or
   StaffProfile;
- CUSTOMER authority never leaks into administrative permission;
- permission/scope/capacity compatibility fails closed.

## Administrative grants

Upper-scope authority is represented by explicit durable grants, not JWT claims
and not hierarchy alone.

The intended bounded relation is equivalent to:

```text
internal User
+ administrative scope
+ bounded PermissionCode
```

Required structural compatibility includes:

- Platform permission cannot be persisted at Organization scope;
- Organization permission cannot be persisted at Platform scope;
- Tenant business permission is not automatically an administrative grant;
- incompatible actor capacity is rejected;
- external provider/JWT claims cannot materialize durable Platform or
   Organization authority.

The exact persistence representation is deferred until its PostgreSQL RED.

## Permission vocabulary

Administrative permission names remain system-owned and bounded.

The issue currently requires capabilities equivalent to:

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

Names may be refined only with compatible executable contracts.

No wildcard such as `PLATFORM_ALL` or generic `ORGANIZATION_ADMIN` is introduced
for convenience.

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

Persistence must eventually prove:

- one-parent Tenant placement;
- conditional mutation using expected source;
- concurrent attach correctness;
- concurrent move correctness;
- stale move rejection;
- stale detach rejection;
- destination lifecycle stability during mutation.

Narrow row locking may be used where executable evidence proves it is necessary
to preserve an ACTIVE destination precondition until transaction commit.

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

The next potential migration number after this checkpoint is V26, but it is not
authorized until executable placement or other persistence evidence requires it.

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

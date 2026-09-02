# ADR-0012 — Tenant Workforce Authority Lifecycle

Status: DESIGNED

## Context

ADR-0011 established a first-class authorization kernel with Staff and Customer
personas, scoped RoleAssignments, PermissionEnvelopes, AuthorityBands, bounded
overrides, delegation controls and deny-by-default authorization.

It deliberately did not make User identity or TenantMembership responsible for
organizizational employment facts.

OH-014 now needs to represent Staff as a real Tenant-scoped workforce
relationship.

The design must preserve these distinctions:

```text
User
    != TenantMembership
    != StaffProfile
    != Department
    != JobPosition
    != RoleDefinition
    != Permission
```

A person's organizational position may constrain the maximum authority available
to that Staff relationship, but it is not itself a RoleDefinition and does not
automatically grant operational permissions.

## Decision

Introduce a first-class `workforce` Spring Modulith module.

`workforce` owns:

- StaffProfile;
- Staff lifecycle status;
- Department;
- JobPosition;
- reporting/supervisor relationships;
- explicit organizational authority changes;
- workforce authority/audit facts.

`users` remains owner of User identity and TenantMembership.

`authorization` remains owner of executable permissions, roles, delegation,
PermissionEnvelope and authorization decisions.

`security` remains authentication and trusted-request infrastructure.

### StaffProfile

StaffProfile represents one User participating as Staff in one Tenant.

Its durable identity is independent from User identity.

Initial identity model:

```text
staffId
userId
tenantId
status
```

User and Tenant references are opaque UUIDs owned by their respective modules.

The initial lifecycle vocabulary is:

- `ACTIVE`;
- `INACTIVE`.

Additional employment lifecycle distinctions must be introduced only when a
concrete workflow requires them.

A single User may have independent StaffProfiles in different Tenants.

No email, name, phone, address or authentication-provider claim is required by
the workforce authorization model.

### Department

Department is a configurable Tenant-scoped organizational grouping.

Department:

- does not grant permissions;
- is not a RoleDefinition;
- does not imply an AuthorityBand;
- may be referenced by later Staff placement/history;
- remains Tenant-owned.

Changing Department must not silently change RoleAssignments.

### JobPosition

JobPosition is a Tenant-scoped organizational responsibility definition.

It has:

- stable position identity;
- Tenant scope;
- configurable business code/title;
- explicit AuthorityBand;
- explicit maximum PermissionEnvelope.

JobPosition is not RoleDefinition.

The AuthorityBand constrains organizational/delegation authority.

The PermissionEnvelope is an upper bound, not an automatic grant.

Therefore:

```text
MANAGEMENT position
+ envelope {CATALOG_VIEW}
!= every operational permission below MANAGEMENT
```

Higher organizational rank never means blind permission inheritance.

### Authorization dependency direction

`workforce` may consume the stable authorization policy vocabulary required to
describe a position ceiling:

- AuthorityBand;
- PermissionCode;
- PermissionEnvelope.

The dependency direction is:

```text
workforce -> authorization
```

Authorization must not depend on workforce persistence or workforce entities.

Later authorization orchestration receives the effective workforce ceiling
through an application boundary rather than querying workforce tables directly.

### Staff placement and effective workforce authority

`StaffPlacement` is a Tenant-scoped organizational relationship that binds one
StaffProfile to one Department and one JobPosition.

Placement remains separate from Staff identity so organizational movement does
not rewrite User identity or the durable identity of the Staff relationship.

A valid placement requires StaffProfile, Department and JobPosition to share the
same Tenant.

`WorkforceAuthorityResolver` derives the current workforce ceiling from:

```text
StaffProfile lifecycle state
+ exact StaffPlacement
+ exact JobPosition
-> EffectiveWorkforceAuthority
```

For ACTIVE Staff, the result carries the JobPosition AuthorityBand and its exact
PermissionEnvelope.

For INACTIVE Staff, the result contains no AuthorityBand and an empty
PermissionEnvelope even when historical placement data still exists.

`WorkforcePermissionCeiling` intersects candidate authorization permissions with
the current workforce PermissionEnvelope. Therefore durable RoleAssignments or
ALLOW overrides that became stale after a demotion cannot remain effective merely
because those authorization records still exist.

This composition rule is a correctness boundary; destructive cleanup of stale
roles/overrides is not required to make demotion effective.
### Application authority composition

Workforce authority is exposed through a framework-neutral application boundary
rather than by exposing workforce persistence to authorization.

The current composition is:

```text
authorization candidate PermissionEnvelope
+ current EffectiveWorkforceAuthority PermissionEnvelope
-> bounded effective PermissionEnvelope
```

`BoundedWorkforceAuthorizationService` performs only this restrictive
intersection. It does not load RoleAssignments, RoleDefinitions or permission
overrides and therefore does not duplicate authorization persistence ownership.

Privileged position mutation represents actor and target Staff identities
separately.

The workforce-side privileged mutation policy fails closed when:

- actor or target does not match the explicit request identity;
- actor or target belongs to another Tenant;
- actor is inactive;
- target is inactive;
- the upstream privileged authorization decision did not allow the operation;
- actor and target are the same Staff relationship.

Rejecting self-directed privileged position mutation is intentionally stronger
than rejecting only a literal PROMOTION: a lateral JobPosition change can
alter the PermissionEnvelope without changing AuthorityBand and must not become
a self-escalation path.

The concrete authorization permission/use case for workforce administration is
not invented in this batch. The application boundary consumes an explicit
already-resolved privileged authorization outcome until a concrete executable
permission contract is introduced.
### Reporting relationships

Supervisor/reporting relationships are organizational graph edges.

They:

- are Tenant-scoped;
- grant no permission;
- cannot point to the same StaffProfile;
- cannot cross Tenant boundaries;
- cannot create a reporting cycle.

The reporting graph is therefore organizational context only.

A future authorization policy may consume an explicit reporting fact when a real
business rule requires it; no implicit permission follows from the graph itself.

### Promotion and demotion

Promotion/demotion is an explicit organizational authority change.

It is not implemented by editing roles or permission overrides.

The effective authorization ceiling derives from the StaffProfile's current
workforce position and state.

A later demotion therefore contracts the effective PermissionEnvelope.

Roles or ALLOW overrides that remain durably recorded outside that new envelope
must no longer become effective. Automatic destructive cleanup is not required
for correctness if the authorization boundary continues applying the current
workforce ceiling.

Promotion may enlarge the ceiling only through an explicitly authorized
workforce operation.

### Inactive Staff

An `INACTIVE` StaffProfile contributes no workforce authorization ceiling.

Later composition must therefore resolve inactive Staff to an empty effective
PermissionEnvelope regardless of stale RoleAssignments or overrides.

Historical workforce/audit evidence is retained rather than deleting the
StaffProfile merely to revoke authority.

### Privileged changes

Ordinary Staff maintenance and organizational-authority changes are distinct
operations.

The later mutation boundary must reject:

- self-promotion;
- delegation beyond the actor's authority boundary;
- unauthorized Tenant-governance elevation;
- removal/demotion of the final viable Tenant-governance administrator when the
  concrete mutation path is introduced.

Concurrency-sensitive privileged invariants use PostgreSQL, not JVM-local locks.

### Persistence

OH-014 starts with `V13`.

`V1` through `V12` are accepted and immutable.

The initial workforce schema will persist only concrete workforce state needed
by this slice.

There will be no cross-schema foreign keys from workforce tables into `users`,
`tenants` or `access_control`.

Cross-module referential/business validation remains explicit at application
boundaries.

PostgreSQL constraints must reject structurally invalid workforce state whenever
the invariant is relationally enforceable.

### Audit evidence

Privilege-significant workforce changes require append-oriented audit evidence.

Audit records contain only bounded operational facts required for traceability,
such as:

- internal actor identifier;
- affected Staff identifier;
- Tenant scope;
- action type;
- before/after authority facts where applicable;
- outcome/reason;
- correlation metadata.

Audit storage must not duplicate unnecessary PII, JWTs or request payloads.

Audit correctness follows the business mutation transaction.

`REQUIRES_NEW` is not introduced merely to make an attempted mutation survive a
rollback, because that could create a durable record implying an organizational
change that never committed.

### Privacy

Workforce is not an employee analytics or HRIS module.

OH-014 does not introduce:

- payroll;
- compensation;
- attendance;
- health information;
- performance scoring;
- productivity rankings;
- behavioral profiling.

Those require separate concrete purposes and privacy analysis.

## Verification plan

ADR-0012 remains DESIGNED until executable OH-014 evidence demonstrates the
implemented slice.

Required evidence includes:

- `workforce` is a distinct Spring Modulith module;
- module dependencies remain acyclic;
- StaffProfile is Tenant-scoped and User-identity neutral;
- the same User can hold independent Staff relationships across Tenants;
- Department grants no authority;
- JobPosition is separate from RoleDefinition;
- AuthorityBand does not imply permissions;
- position PermissionEnvelope remains an explicit upper bound;
- reporting relationships reject self-links, cross-Tenant links and cycles;
- reporting relationships grant no permissions;
- inactive Staff resolves to no effective workforce authority;
- promotion/demotion is explicit and bounded;
- stale roles/ALLOW overrides cannot bypass a contracted workforce envelope;
- privileged/self-escalating mutations fail closed;
- applicable last-governance invariants are concurrency-safe;
- workforce audit evidence is append-oriented and privacy-safe;
- `V13` reconstructs the workforce schema from an empty PostgreSQL database;
- `V1` through `V12` remain unchanged;
- PostgreSQL constraints reject structurally invalid workforce state;
- `git diff --check` passes;
- full `mvnw clean verify` passes;
- required pull-request workflows pass;
- final Codex review has no unresolved irregularity before ADR promotion/merge.

## Explicitly deferred

OH-014 does not build:

- payroll or compensation;
- attendance/leave management;
- recruiting;
- full Staff PII profile management;
- employee performance scoring;
- workforce analytics warehouse;
- Network/Organization workforce administration;
- Platform workforce administration;
- complete browser UI;
- external HR integrations.

## References

This decision builds directly on:

- ADR-0011 — Identity Personas and Scoped Authorization Kernel;
- NIST RBAC concepts for role hierarchy and separation of duties;
- OWASP Authorization Cheat Sheet principles of least privilege and deny by
  default;
- LGPD/ANPD purpose, necessity and proportionality principles for personal-data
  processing.

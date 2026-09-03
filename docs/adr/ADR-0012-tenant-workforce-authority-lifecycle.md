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

The concrete privileged position-change boundary does not accept caller-supplied
before/after AuthorityBand, JobPosition or PositionChange facts.

`PrivilegedPositionChangeExecutionService` resolves, inside one workforce
transaction:

```text
actor StaffProfile
+ target StaffProfile
+ actor placement / JobPosition
+ target current placement / JobPosition
+ requested target JobPosition
+ persisted JobPosition PermissionEnvelopes
-> authoritative PositionChange
```

`PostgreSqlWorkforcePositionChangeRepository.loadForUpdate(...)` obtains
PostgreSQL row locks while resolving those facts. The subsequent placement update
also requires the expected current position, so stale concurrent state cannot be
silently overwritten.

The concrete mutation boundary rejects or fails closed for:

- self-promotion/self-directed privileged position mutation;
- delegation beyond the actor's authority boundary;
- unauthorized Tenant-governance elevation;
- missing or cross-Tenant Staff/position facts;
- a same-position request that would otherwise create a false APPLIED change;
- removal/demotion of the final viable Tenant-governance administrator through
  the V15-protected mutation paths.

A same-position request is a committed denial, not an organizational mutation.
It emits bounded PRIVILEGED_MUTATION / DENIED / POSITION_UNCHANGED evidence
without changing StaffPlacement.

Competing equivalent position changes serialize on PostgreSQL-authoritative
state. After the first transaction commits, the competing execution re-resolves
the placement and treats the now-identical requested position as
POSITION_UNCHANGED.

Concurrency-sensitive privileged invariants use PostgreSQL, not JVM-local locks.

### Persistence

OH-014 starts with `V13`.

`V1` through `V15` are accepted and immutable.

The workforce schema persists only concrete workforce state needed by this
slice. `V16` adds append-oriented audit evidence for privilege-significant
workforce changes without changing the accepted V13-V15 relational model.

There will be no cross-schema foreign keys from workforce tables into `users`,
`tenants` or `access_control`.

Cross-module referential/business validation remains explicit at application
boundaries.

PostgreSQL constraints must reject structurally invalid workforce state whenever
the invariant is relationally enforceable.

### Initial PostgreSQL workforce foundation

`V13` materializes the initial workforce state as exactly:

- `workforce.staff_profiles`;
- `workforce.departments`;
- `workforce.job_positions`;
- `workforce.job_position_permissions`;
- `workforce.staff_placements`;
- `workforce.reporting_relationships`.

Staff, Department and JobPosition retain opaque Tenant ownership. Composite
Tenant/identity uniqueness allows placement and reporting foreign keys to prove
that referenced workforce records belong to the same Tenant.

`job_position_permissions` persists membership of the position's maximum
PermissionEnvelope by permission code. It deliberately does not create a foreign
key into `access_control.permissions`; cross-module permission-vocabulary
validation remains an application/module boundary.

The V13 foundation rejects relationally enforceable invalid state including:

- unsupported Staff lifecycle status;
- duplicate User/Staff relationship inside the same Tenant;
- duplicate Department or JobPosition code inside one Tenant;
- unsupported AuthorityBand values;
- malformed persisted permission codes;
- cross-Tenant Staff placement references;
- self-reporting relationships;
- cross-Tenant reporting references.

The V13 foundation itself does not arbitrate reporting cycles or lifecycle races.
Those concurrency-sensitive reporting invariants are introduced separately by
V14.

### PostgreSQL reporting integrity arbitration

V14 makes PostgreSQL the correctness boundary for concurrent reporting
mutations.

Reporting-edge creation/update and Staff transition to INACTIVE acquire the
same transaction-scoped PostgreSQL advisory lock derived from the Tenant scope.
The lock is database-visible and therefore coordinates independent application
replicas without relying on JVM-local synchronization.

After obtaining the Tenant lock, reporting mutation validates that the supervisor
is still ACTIVE and recursively checks the committed Tenant reporting graph for
a path from the proposed subordinate back to the proposed supervisor. A detected
path rejects the mutation because adding the candidate edge would close a cycle.

A transition to INACTIVE obtains the same Tenant lock and is rejected while the
StaffProfile still owns an active supervisor edge. Deactivation therefore
requires supervisor relationships to be removed/reassigned before the lifecycle
transition can commit.

This shared arbitration means competing operations such as A -> B versus
B -> A, or supervisor-edge creation versus supervisor deactivation, cannot both
commit into an invalid final state.

ReportingStructurePolicy remains the framework-neutral domain precondition for
ordinary in-memory composition; PostgreSQL provides the durable multi-replica
correctness boundary.

V14 does not implement last-governance arbitration or append-oriented audit
persistence. Those remain separate high-assurance boundaries.

### PostgreSQL Tenant-governance viability arbitration

V15 defines the workforce-side viable Tenant-governance relationship as:

```text
ACTIVE StaffProfile
+ current Tenant StaffPlacement
+ JobPosition AuthorityBand.TENANT_GOVERNANCE
```

This represents organizational governance viability only. It is not a
RoleAssignment, does not grant an executable permission and does not make
workforce responsible for authorization persistence.

Mutations capable of reducing this viable-governance population acquire the same
transaction-scoped PostgreSQL advisory lock derived from the Tenant scope.

The protected mutation paths are:

- ACTIVE Staff transition to INACTIVE;
- StaffPlacement deletion;
- StaffPlacement movement away from a TENANT_GOVERNANCE position;
- JobPosition AuthorityBand downgrade away from TENANT_GOVERNANCE.

After acquiring the Tenant lock, PostgreSQL re-evaluates committed workforce
state and rejects a mutation when it would leave no other ACTIVE Staff placed in
TENANT_GOVERNANCE.

The invariant deliberately does not require every Tenant to contain governance
state during bootstrap. It protects an existing viable governance population
from being reduced to zero by the concrete authority-removal mutation paths.

Concurrent demotions, deactivation/placement deletion and JobPosition authority
downgrades therefore cannot each independently observe another soon-to-disappear
governance relationship and both commit.

V15 does not inspect RoleAssignments, RoleDefinitions or authorization tables.
Executable authorization and delegation remain owned by authorization.

### Privileged workforce delegation composition

A privileged position mutation remains subject to the existing fail-closed
actor/target/Tenant/upstream-authorization checks before organizational
delegation is evaluated.

The application boundary then requires:

```text
actor current workforce AuthorityBand
    >= requested after AuthorityBand
```

and:

```text
actor authorization-resolved delegation PermissionEnvelope
    contains requested after PermissionEnvelope
```

Both conditions are independent.

The actor's current effective workforce PermissionEnvelope is not treated as the
delegation envelope. Effective business permissions and authority to delegate
permissions remain separate concepts, preserving the OH-013 delegation model.

For the concrete privileged position-change path,
EffectiveWorkforceAuthority is constructed from the actor Staff lifecycle and
actor JobPosition resolved from PostgreSQL inside the transaction. The caller
does not supply an AuthorityBand, JobPosition, PermissionEnvelope for the target
position or a PositionChange snapshot.

The actor delegation envelope and upstream privileged authorization result remain
trusted, already-resolved authorization inputs owned by the authorization
integration boundary.

The workforce application service does not query RoleAssignments,
RoleDefinitions, permission overrides or authorization persistence and does not
duplicate their ownership.

A missing workforce AuthorityBand, insufficient actor AuthorityBand, delegation
envelope overflow or any denial from the underlying privileged mutation policy
fails closed.

Append-oriented audit persistence is materialized by V16 and remains owned by
workforce. The Java application boundary uses a workforce-owned append-only
repository and transaction contract rather than importing the Orders transaction
port.

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

`V16` materializes `workforce.audit_events` with fixed, bounded operational
columns for internal actor/affected Staff identifiers, Tenant scope, action,
outcome/reason, correlation identifier and before/after workforce state. Audit
rows deliberately have no foreign keys to mutable workforce rows so historical
evidence does not disappear or become invalid when current organizational state
changes.

The database rejects UPDATE and DELETE of audit evidence. The application
persistence boundary exposes only `WorkforceAuditRepository.append(...)`.

`PostgreSqlWorkforceAuditRepository` owns no transaction boundary. It participates
in the transaction established by the calling application and translates
adapter-specific persistence failures at the workforce boundary.

`AuditedWorkforceMutationService` coordinates:

```text
workforce mutation
-> audit append
```

inside one invocation of `WorkforceTransactionExecutor`. The Spring adapter
materializes that workforce-owned contract with `TransactionOperations`; it does
not import the Orders transaction port.

The resulting semantics are:

- successful mutation and APPLIED audit evidence commit together;
- audit persistence failure rolls back the business mutation;
- mutation failure prevents the audit append and rolls back prior mutation work;
- a policy/no-op denial that performs no business mutation may commit bounded
  DENIED evidence in the same normal transaction;
- audit is not independently committed merely to preserve an attempted action.

`PrivilegedPositionChangeExecutionService` applies those semantics to the
concrete position-change path. An authorized position mutation appends
POSITION_CHANGED or POSITION_AUTHORITY_CHANGED / APPLIED according to the
persisted before/after AuthorityBand. A rejected privileged request appends
PRIVILEGED_MUTATION / DENIED with a bounded reason code while leaving the
placement unchanged.

This distinction is intentional: a committed denial records that a privileged
attempt was rejected; a mutation that actually fails and rolls back cannot leave
durable evidence implying that the organizational change committed.

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
- privileged position changes derive Staff/placement/position authority facts
  from PostgreSQL rather than caller-supplied before/after authority claims;
- same-position requests cannot produce false APPLIED position-change evidence;
- competing equivalent privileged position changes re-evaluate the latest
  PostgreSQL-authoritative placement after lock serialization;
- applicable last-governance invariants are concurrency-safe;
- workforce audit evidence is append-oriented and privacy-safe;
- audit UPDATE/DELETE is rejected and persisted evidence uses bounded columns;
- workforce mutation and audit evidence commit or roll back together;
- no independent REQUIRES_NEW audit transaction exists;
- `V13` reconstructs the workforce foundation from an empty PostgreSQL database;
- `V16` reconstructs append-oriented audit storage through the full Flyway chain;
- `V1` through `V15` remain unchanged;
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

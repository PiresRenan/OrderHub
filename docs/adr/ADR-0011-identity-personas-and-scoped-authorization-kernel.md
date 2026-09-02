# ADR-0011 — Identity Personas and Scoped Authorization Kernel

Status: TESTED

## Context

OrderHub has completed the identity and reliability foundations required before
business authorization can be introduced responsibly.

ADR-0007 / OH-009 established:

- one opaque internal User UUID;
- a first-class `users` module;
- durable TenantMembership;
- membership uniqueness and User referential integrity;
- explicit separation between membership and authorization;
- no roles or permissions embedded in User or TenantMembership.

ADR-0008 / OH-010 established:

- OAuth2 Resource Server authentication;
- validated external identity `(issuer, subject)`;
- resolution to the stable internal User UUID;
- trusted Tenant context only after membership proof;
- no trust in JWT roles, Tenant claims or arbitrary provider claims as business
  authorization.

OH-011 and OH-012 then established real Catalog, Inventory and Order boundaries
whose future administrative surfaces require authorization beyond membership.

The next requirement is broader than a single Inventory permission. OrderHub must
support people operating in distinct capacities, constrained workforce
responsibility, customer self-service and later hierarchical administration.

At the same time, the system must avoid several common authorization failures:

- treating User identity as a job/persona record;
- turning TenantMembership into an overloaded RBAC object;
- using one `ADMIN` boolean or `hasRole('ADMIN')` throughout controllers;
- making management hierarchy equivalent to blind permission inheritance;
- allowing direct/custom permissions to become an implicit promotion mechanism;
- accepting externally supplied JWT roles as the durable authorization source;
- modeling Customers as low-privilege employees;
- collecting personal data merely because it could be analytically interesting
  someday.

OH-013 therefore establishes a first-class authorization kernel and the persona
vocabulary needed by later Staff and Customer domains before exposing
administrative business APIs.

## Decision summary

Introduce a new first-class Spring Modulith module:

`authorization`

It owns framework-neutral business authorization concepts and decisions.

The enduring conceptual separation is:

```text
User identity
    != Tenant membership
    != persona
    != organizational position
    != authority ceiling
    != role
    != permission
    != resource relationship
```

The authorization equation is conceptually:

```text
actor identity
+ active persona
+ effective scope
+ organizational/authority envelope
+ role permissions
+ bounded user overrides
+ resource relationship / contextual policy
+ separation-of-duty constraints
------------------------------------------------
ALLOW or DENY
```

The default decision is DENY.

## Module ownership

### Users

`users` remains owner of:

- User identity;
- TenantMembership;
- external identity bindings;
- persistence required by those concepts.

OH-013 does not add roles, permissions, Staff fields or Customer fields to the
User aggregate.

TenantMembership continues to mean only:

> this internal User is associated with this Tenant.

Membership alone grants no business permission.

### Authorization

The new `authorization` module owns:

- authorization persona vocabulary;
- Permission catalog/model;
- RoleDefinition;
- role mutability/protection classification;
- RoleAssignment;
- AuthorityBand;
- PermissionEnvelope;
- bounded per-user PermissionOverride policy;
- authorization constraints, including separation of duties;
- framework-neutral authorization request/decision models;
- effective permission evaluation;
- authorization persistence ports/adapters introduced by OH-013.

Authorization stores identity references as opaque UUIDs and must not import
Users, Tenants, Orders, Catalog or Inventory persistence/domain internals.

Cross-module existence/lifecycle checks use explicit application contracts when a
concrete mutation requires them.

### Security

`security` remains responsible for:

- bearer authentication;
- JWT verification/validation infrastructure;
- external-to-internal identity adaptation;
- trusted request context construction;
- Spring Security integration.

Security is not the durable RBAC database.

Spring Security may adapt a framework-neutral OrderHub authorization decision,
but core permissions and policy semantics must not be scattered as controller-
local `hasRole(...)` expressions.

### Workforce — later slice

A future `workforce` module owns:

- StaffProfile;
- Department;
- JobPosition;
- reporting/supervisor relationships;
- employment status/lifecycle;
- promotion/demotion history;
- mapping from organizational position to an authorization ceiling/envelope.

These are organizational facts, not attributes added to User.

### Customers — later slice

A future `customers` module owns:

- CustomerProfile;
- commerce/customer lifecycle;
- authenticated-User binding when appropriate;
- customer preferences and customer-owned relationships;
- later customer activity projections.

Customer ownership relationships provide policy inputs to authorization. They do
not become employee roles.

## Personas

OH-013 defines the initial persona vocabulary:

```text
STAFF
CUSTOMER
```

A persona expresses the capacity in which an internal User is attempting to act.

A User may eventually be:

- STAFF in Tenant A;
- CUSTOMER in Tenant B;
- CUSTOMER and STAFF through different domain relationships without either
  relationship granting authority to the other.

Persona is not globally attached to User.

Persona alone grants no permission.

### STAFF

STAFF authorization is primarily role/permission based and constrained by:

- effective Tenant/scope;
- authority ceiling;
- permission envelope;
- role assignments;
- bounded user overrides;
- policy/SoD constraints.

### CUSTOMER

CUSTOMER authorization is primarily relationship/resource based.

Typical future decisions include:

- view own profile;
- update own allowed preferences;
- view own Order;
- perform an Order action only when both ownership and Order state permit it;
- access own addresses/returns when those resources exist.

OH-013 does not implement those customer endpoints. It prevents the kernel from
assuming that all authorization is employee RBAC.

## Trusted actor context

OH-010 deliberately minimized `TrustedTenantContext` to Tenant identity because
Orders needed trusted Tenant provenance but not actor authorization.

Fine-grained authorization and privilege audit now require internal actor
identity after membership verification.

OH-013 therefore introduces/evolves a framework-neutral trusted actor context
with at least:

```text
userId
requested/effective tenantId
```

The context contains internal identity, not raw JWT claims.

It must not contain:

- bearer token;
- raw issuer/subject unless independently required by authentication diagnostics;
- provider roles;
- provider Tenant claims;
- arbitrary authentication attributes.

Existing Orders behavior may continue to consume only the Tenant portion. The
change must not cause Orders domain/application code to depend on Spring
Security/JWT/OAuth types.

## Scope model

OH-013 implements the first concrete authorization scope:

```text
TENANT(tenantId)
```

The long-term hierarchy remains:

```text
PLATFORM
  -> NETWORK / ORGANIZATION
      -> TENANT
          -> RESOURCE
```

OH-013 must not invent functioning Platform or Network roles before those domains
exist.

The Tenant-scoped model must, however, avoid assumptions that would prevent
future scope expansion.

A User may hold different assignments in different Tenants.

No assignment for Tenant A authorizes Tenant B.

## Permission model

Permission is the atomic executable authorization vocabulary.

Permissions are system-owned and versioned by OrderHub.

The persona classification of an existing permission code is immutable.
`role_permissions` also enforces persona compatibility at the PostgreSQL
boundary, preventing a Staff RoleDefinition from durably containing a
Customer-only permission or vice versa.

Tenants may later compose allowed permissions into custom roles, but they cannot
invent arbitrary permission strings that magically become executable code.

Representative permission families include:

```text
TENANT_MEMBERS_VIEW
TENANT_MEMBERS_MANAGE
TENANT_ROLES_VIEW
TENANT_ROLES_ASSIGN
TENANT_PRIVILEGED_ROLES_ASSIGN

CATALOG_VIEW
CATALOG_MANAGE
CATALOG_PRICE_MANAGE

INVENTORY_VIEW
INVENTORY_RECEIVE
INVENTORY_ADJUST
INVENTORY_POLICY_MANAGE

ORDERS_VIEW
ORDERS_CREATE
ORDERS_MANAGE
ORDERS_APPROVE

AUDIT_VIEW
```

The precise initial catalog is finalized in executable OH-013 code/tests and
must remain intentionally small enough to correspond to real or explicitly
planned OrderHub operations.

Permission metadata may classify at least:

- functional domain;
- compatible persona;
- privilege/sensitivity category needed by policy.

Permission metadata does not make all permissions below some numeric level
implicitly available.

## Organizational hierarchy is not permission inheritance

OrderHub needs recognizable responsibility levels such as:

```text
OPERATIONAL
SUPERVISORY
COORDINATION
MANAGEMENT
TENANT_GOVERNANCE
```

These are authorization authority bands, not concrete job titles.

Later JobPositions such as Inventory Operator, Inventory Coordinator or Orders
Manager map organizational reality to an authority ceiling/envelope.

A higher authority band means a larger possible delegation/policy boundary. It
does not automatically inherit every operational permission associated with a
lower role.

For example, an Inventory Manager may have authority to manage inventory policy
without necessarily receiving every warehouse execution permission simply
because those actions are 'lower' in an org chart.

Explicit permission sets remain authoritative.

## Permission envelopes

A PermissionEnvelope defines the maximum permission set that may be made
effective for an actor/position under a given organizational policy.

Core invariant:

```text
effective permissions ⊆ effective permission envelope
```

This prevents permission customization from becoming implicit promotion.

The authorization kernel models and enforces this invariant even before OH-014
introduces concrete Staff positions and promotion workflows.

Future workforce configuration provides the actor's organizational envelope to
the authorization boundary through an explicit contract rather than by letting
Authorization query workforce persistence directly.

## Role definitions

A role is a reusable set of system permissions.

It is administrative ergonomics, not the primitive authorization check.

Initial role families the model must support include:

- Tenant Administrator;
- User Manager;
- Catalog Manager;
- Inventory Manager;
- Order Manager;
- Inventory Operator;
- Order Operator;
- Auditor;
- Restricted Staff.

Roles have a mutability/protection class.

The initial classification model is:

### SYSTEM_LOCKED

OrderHub-owned roles/capabilities whose permission definition cannot be changed
through Tenant administration.

Future Platform root/break-glass concepts belong to strong system protection and
are not ordinary Tenant roles.

### TENANT_PROTECTED

High-impact Tenant governance roles whose ordinary assignment/definition rules
are stricter than normal functional roles.

`TENANT_ADMINISTRATOR` is expected to be protected.

### BUILTIN_FUNCTIONAL

OrderHub-provided functional role templates such as Inventory Manager or Order
Operator.

The later workforce/custom-role slice may allow copying/deriving from them but
must not mutate the canonical system definition in place.

### TENANT_CUSTOM

Future Tenant-defined roles composed from the existing system Permission
catalog and constrained by an applicable PermissionEnvelope.

Tenant custom roles cannot create new executable permissions.

OH-013 enforces ordinary role-definition mutation through a framework-neutral `TenantCustomRoleMutationPolicy`. Only `TENANT_CUSTOM` definitions may enter that path. Stable role code, persona and authority band cannot be rewritten, the persisted definition envelope cannot be widened, and replacement state must remain inside the acting administrator's explicit delegation envelope.

Role code resolution must remain unambiguous across the global system namespace
and Tenant-owned custom definitions.

V12 therefore reserves every stable role code in exactly one durable namespace:
`SYSTEM` or `TENANT_CUSTOM`.

`SYSTEM` covers non-custom OrderHub definitions. `TENANT_CUSTOM` covers
Tenant-owned definitions.

The same `TENANT_CUSTOM` code may be used independently by different Tenants.
A code reserved by one namespace cannot later shadow or be reinterpreted as the
other namespace.

The reservation survives deletion of an individual RoleDefinition. Stable role
codes therefore cannot silently change authorization meaning over time.

V12 also makes the `code` of an existing RoleDefinition immutable. A durable
`role_id` therefore cannot be retained while silently rewriting the role's stable
authorization code.

OH-013 also exposes a read-only durable RoleDefinition repository. The
repository resolves the definition visible in one Tenant scope and reconstructs
its atomic permissions from `role_permissions`.

For persisted OH-013 definitions, the persisted permission membership is also
the definition's current PermissionEnvelope. A wider editable custom-role
envelope is not invented before the deferred custom-role administration
lifecycle exists.

## Role assignment

A RoleAssignment is scoped authorization state.

Conceptually it identifies:

```text
userId
persona
scope
roleDefinition
```

Initial assignments are Tenant-scoped.

V12 additionally rejects a durable assignment when its `tenant_id` does not
match the owning Tenant of a `TENANT_CUSTOM` RoleDefinition. Global system role
definitions remain usable from validated Tenant scopes, while a Tenant-owned role
cannot be referenced by an assignment persisted for another Tenant.

RoleDefinition Tenant ownership is immutable after creation. A custom role cannot
be moved from one Tenant to another while preserving a stable `role_id`, which
prevents existing assignments from becoming cross-Tenant authorization state
after they have already passed insertion-time validation.

RoleAssignment must not be embedded inside User or TenantMembership.

The same User may have unrelated RoleAssignments in different Tenants.

Assignments for STAFF require an independently valid Staff/Tenant relationship
once workforce exists. During OH-013, membership/existence preconditions are
resolved only through explicit application contracts where needed.

## Direct permission overrides

OrderHub supports future account-specific customization without allowing ad-hoc
privilege escalation.

A direct override has:

```text
permission
ALLOW | DENY
scope
subject
```

Rules:

- `DENY` may reduce effective authority;
- `ALLOW` may add a permission only when it is already inside the actor's
  effective PermissionEnvelope;
- an override cannot cross scope;
- an override cannot mutate a system Permission definition;
- an override cannot implicitly promote the actor's organizational authority;
- privileged permissions may require stronger policy than ordinary overrides.

Direct overrides are exceptional policy inputs rather than the normal mechanism
for constructing access.

## Promotion and demotion

Promotion/demotion is not implemented by accumulating permissions.

The future workforce flow changes JobPosition/authority envelope explicitly and
records history/audit evidence.

Only after that organizational change may authorization assignments validly use
capabilities inside the new envelope.

OH-013 enforces the kernel constraints that make accidental privilege-based
promotion impossible; OH-014 owns the actual workforce lifecycle use cases.

## Delegation and self-escalation

An actor may grant another actor only authority that the grantor is explicitly
allowed to delegate.

Possessing a permission is not automatically equivalent to possessing the right
to delegate it.

Ordinary role assignment and privileged role assignment therefore remain
distinct permissions/policies.

The kernel must be able to reject:

- self-assignment of a role outside the actor's authority/delegation boundary;
- a User Manager assigning Tenant Administrator without privileged delegation;
- assignment of a custom role containing permissions outside the target or
  grantor envelope;
- cross-Tenant role assignment used as authority in another Tenant.

OH-013 enforces this boundary through `RoleDelegationPolicy`. Ordinary assignment requires `TENANT_ROLES_ASSIGN`. Self-assignment, `TENANT_PROTECTED` roles and `TENANT_GOVERNANCE` roles additionally require `TENANT_PRIVILEGED_ROLES_ASSIGN`. `SYSTEM_LOCKED` roles are not assignable through the ordinary Tenant delegation path. The proposed assignment must remain in the actor's trusted Tenant scope, within the actor authority band, inside the actor delegation envelope and inside the target permission envelope.

## Trusted actor context

Authentication continues to resolve external JWT identity into only the stable
internal OrderHub User identifier.

For authorization-capable servlet surfaces, Security may additionally project a
`TrustedActorContext` containing:

- internal `userId`;
- trusted internal `tenantId`.

The context is created only after the existing exact User/Tenant membership
boundary accepts the caller-requested Tenant selector.

Raw JWT Tenant claims, provider roles, scopes and authorities are not copied into
this context and do not become business authorization inputs.

`TrustedTenantContext` remains valid for existing boundaries that need only
already-proven Tenant authority. Adding `TrustedActorContext` therefore does not
weaken or retroactively broaden those contracts.

## Separation of duties

Authorization must represent constraints independently from RoleDefinition.

The design must support static and later dynamic separation-of-duty rules.

Examples of later high-impact constraints include incompatible combinations of:

- requesting and approving the same financial/operational action;
- privileged administration and independent audit responsibilities;
- other domain-specific duties when those workflows exist.

OH-013 establishes a framework-neutral AuthorizationConstraint contract and the
first StaticSeparationOfDutyConstraint implementation.

Static SoD is modeled as a set of mutually exclusive RoleAssignment codes for
the same internal User, persona and Tenant scope. A matching conflict can only
reduce an otherwise eligible decision to DENY; a constraint never creates a
permission grant.

Constraint evaluation itself is fail-closed. Missing, malformed or failing
constraint state cannot result in ALLOW.

OH-013 does not configure invented business conflicts for workflows OrderHub
does not yet have. Dynamic/session/history-based SoD remains a later extension
when a concrete workflow supplies the required execution context.

## Authorization decision semantics

Authorization is deterministic and deny-by-default.

A Tenant-scoped STAFF decision conceptually evaluates:

1. authenticated internal actor exists;
2. trusted Tenant context is proven;
3. requested persona is compatible with the authorization path;
4. scope matches the assignment/policy scope;
5. Permission is known to OrderHub;
6. role permissions plus eligible ALLOW overrides are calculated;
7. DENY overrides are applied;
8. effective permission set is intersected with the effective PermissionEnvelope;
9. delegation/SoD/context constraints are applied;
10. requested Permission must remain effective after every restriction.

Unknown permission, missing required state, invalid scope, policy inconsistency or
constraint failure returns DENY.

The evaluator must not fail open because authorization state is unavailable.
Durable STAFF authorization must also observe one coherent privilege-state
snapshot. RoleAssignments, RoleDefinitions/role permissions and direct overrides
participating in one decision cannot be independently observed from different
committed database moments.

The PostgreSQL authorization adapter therefore executes the complete durable
decision-read sequence under an independent read-only `REPEATABLE READ`
transaction. The independent physical transaction prevents an enclosing
`READ_COMMITTED` business transaction from silently downgrading authorization
snapshot isolation.

This is a consistency boundary, not a locking strategy: competing administrative
writes remain free to commit, while the in-flight authorization decision
continues against its original coherent snapshot.

Adversarial PostgreSQL evidence covers both a competing assignment/role-permission
change that would otherwise create an impossible transient ALLOW and concurrent
SYSTEM versus TENANT_CUSTOM role-code reservation.

Customer/resource authorization will extend the contextual relationship inputs in
later slices without granting customer access through Staff roles.

OH-013 now exposes an executable framework-neutral relationship policy hook.
Resource-owning modules resolve bounded relationship facts such as
`RESOURCE_OWNER` and supply those facts to Authorization; Authorization does not
import foreign resource entities or persistence.

The relationship context carries internal actor identity, persona, Tenant scope
and bounded relationship facts, but intentionally carries no resource identifier.
This allows future CUSTOMER ownership policy to compose with resource-domain
state without turning Customer into a Staff role or duplicating resource
identifiers into authorization state.

Authorization decision observability is bounded by construction. The application
observation model exposes only `decision`, `persona`, system-owned `permission`
and a finite `reason` vocabulary.

The Micrometer adapter therefore cannot create metric labels from User, Tenant,
resource or external identity-provider identifiers through this boundary.
Telemetry failure is non-authoritative and cannot alter an authorization
decision.

## Persistence direction

OH-013 introduces a new PostgreSQL schema owned by the authorization module:

`access_control`

The Java application module remains named `authorization`. The PostgreSQL
namespace intentionally uses `access_control` because `AUTHORIZATION` is a
reserved PostgreSQL keyword and is also part of the `CREATE SCHEMA` grammar.
Using a non-reserved database identifier avoids permanent quoted-identifier
requirements throughout migrations and persistence adapters.

Accepted migrations V1 through V11 remain immutable.

The first authorization migration is V12.

The persistence model must support durable representation of at least:

- system Permission catalog state required for referential integrity;
- RoleDefinition and its protection/mutability class where persistence is needed;
- role-to-permission membership;
- scoped RoleAssignment;
- bounded permission overrides when the model reaches persistence.

OH-013 deliberately does not persist authorization-constraint configuration.
The slice establishes the framework-neutral constraint contract and executable
static SoD behavior, but no business conflict-management lifecycle exists yet.
Durable constraint configuration belongs to the later slice that introduces
concrete conflict administration and its audit lifecycle.

Database constraints must reject structurally invalid values and duplicate
assignments/definitions where a durable uniqueness invariant exists.

Authorization stores User/Tenant references as opaque UUIDs and does not create
cross-module foreign keys into `users` or `tenants` schemas.

Application contracts own cross-module existence checks when a concrete use case
requires them.

No Redis, external policy engine or distributed lock product is introduced for
OH-013 authorization correctness.

## Transaction and concurrency direction

Authorization writes that must preserve one invariant are transactional.

Process-local locks are not correctness mechanisms.

Concurrency tests are required for privilege invariants where two individually
valid-looking updates could jointly violate policy.

Examples include later competing privileged-role changes or last-administrator
protection once the corresponding management operation exists.

OH-013 does not claim concurrency protection for a deferred business operation
until that operation and its invariant are actually implemented.

## HTTP and error semantics

OH-013 does not require a full workforce/customer administration API.

When authorization reaches HTTP-protected surfaces:

- unauthenticated remains authentication failure;
- authenticated but unauthorized is privacy-safe denial;
- failures must not reveal whether an inaccessible User, role, Tenant or resource
  exists;
- authorization identifiers/PII must not be copied into public error details;
- deny-by-default remains active when policy state cannot be safely resolved.

## Observability and audit

Operational authorization metrics use bounded labels only.

Do not use as metric labels:

- userId;
- tenantId;
- roleDefinitionId;
- resourceId;
- external subject;
- email/name/phone;
- arbitrary permission supplied by clients.

Permission codes are system-owned bounded vocabulary and may be used only where
cardinality remains deliberately controlled and operationally justified.

Privilege mutations require append-oriented audit evidence as their concrete use
cases are introduced.

Audit direction includes:

- internal actor reference;
- persona;
- effective scope;
- action;
- target reference/type;
- outcome;
- reason category;
- correlation metadata;
- time;
- no unnecessary copy of private request payloads.

Audit data and general application logs are not interchangeable.

## Data and analytics governance

OrderHub intends to support rich future analysis of workforce performance,
customer behavior and operational outcomes.

The engineering objective is:

> maximize analytical information density while minimizing unnecessary personal
> data processing.

It is not:

> collect every available personal attribute because it might become useful.

LGPD purpose/necessity/proportionality boundaries apply to the design.

Operational systems should retain facts required for legitimate business and
analytical purposes, while avoiding unnecessary duplication of PII.

Future analytical facts should prefer:

- internal/pseudonymous subject identifiers;
- event/action type;
- resource/business category;
- outcome;
- timing/duration;
- channel/context values with bounded meaning;
- historical organizational context when legitimately required for workforce
  analysis.

They should not automatically duplicate:

- raw JWTs;
- bearer credentials;
- complete request bodies;
- customer email/name/phone/address;
- employee contact information;
- sensitive personal data.

Operational facts remain separate from derived analytical scores.

Future employee-performance/customer-profile models must be versioned and
reviewable, especially where automated processing could affect professional or
consumer interests.

High-risk processing requires explicit privacy impact/risk analysis and
safeguards before implementation.

Sensitive personal data is outside ordinary analytics collection by default.

## Alternatives rejected

### Add `role` to TenantMembership

Rejected.

Membership and authorization answer different questions. It would also prevent
clean support for multiple assignments, custom roles, bounded overrides and
future personas.

### Put roles directly on User

Rejected.

Authorization is scope-specific. A global role on User would create authority
leakage across Tenants and conflict with future Network/Platform scopes.

### Trust roles/scopes from JWT

Rejected.

The external provider authenticates identity. OrderHub owns business
authorization and must not outsource Tenant role truth to arbitrary token claims.

### Use only Spring Security `hasRole`

Rejected.

It couples application policy to framework expressions, encourages coarse roles
and does not model OrderHub's scope, envelopes, delegation or relationship
requirements.

Spring Security remains an enforcement adapter around framework-neutral policy.

### Flat RBAC only

Rejected as the long-term model.

Roles are useful administrative permission bundles, but Customers require
ownership relationships and future policies may depend on subject/resource/
environment attributes.

OH-013 therefore uses RBAC as one input to a policy model compatible with ABAC
and relationship-based rules.

### Blind hierarchical role inheritance

Rejected.

Organizational seniority is not equivalent to requiring every operational
permission below it. Higher authority constrains possible delegation/ceiling;
effective permissions remain explicit.

### Unlimited direct per-user permissions

Rejected.

Without PermissionEnvelope constraints, customization becomes a silent privilege
escalation/promotion mechanism and makes access review difficult.

### Model Customer as a Restricted Staff role

Rejected.

Customer self-service authorization is based on commercial/resource ownership
relationships and must not enter the employee authorization hierarchy.

### Collect maximum personal data for future analytics

Rejected.

Future analytical flexibility does not override purpose and necessity. OrderHub
will preserve rich business/activity facts while minimizing unnecessary PII and
separating operational identity from analytical projections.

### Introduce an external authorization/policy engine now

Rejected.

The present requirements can be expressed and verified inside the modular
monolith. An external policy engine would add operational/distributed failure
modes without measured need.

## Consequences

Positive:

- stable User identity remains independent of business persona and job changes;
- TenantMembership remains simple and reusable;
- Staff and Customer can evolve as real domains rather than flags;
- permission customization becomes possible without making promotion implicit;
- roles can match recognizable business functions while permissions remain the
  actual authorization vocabulary;
- future custom roles are constrained by system permissions and explicit
  envelopes;
- cross-Tenant authority remains explicit;
- authorization remains framework-neutral/testable;
- future Platform/Network/Resource scopes remain possible;
- future Customer ownership/ReBAC and contextual ABAC can extend the kernel;
- analytics can become rich without normalizing indiscriminate PII collection.

Trade-offs:

- authorization requires more explicit concepts than a simple role enum;
- effective permission evaluation needs careful deterministic tests;
- role/permission evolution requires versioned governance;
- Staff position management and Customer self-service remain separate slices;
- authorization state adds database reads/caching questions that must be measured
  before optimization;
- privacy governance becomes part of feature design rather than a later cleanup.

These trade-offs are accepted because the simpler alternatives violate concrete
multi-Tenant, hierarchy, customization and persona requirements.

## Verification evidence

ADR-0011 is `TESTED` against the reviewed OH-013 implementation checkpoint
`a4b4f64d371292889bf0515202b6d831ca474cf5` in PR #27.

That exact implementation checkpoint satisfied the acceptance gates before this
ADR status promotion:

- local repository verification completed with 797 tests, 0 failures, 0 errors
  and 0 skipped;
- `git diff --check` was clean;
- Branch Policy completed successfully;
- CI completed successfully;
- Platform CI completed successfully;
- Codex completed its review with a `+1` reaction and produced no review
  comments, inline findings or unresolved irregularities;
- the PR base remained
  `pre-release@40e0283c498ee4e575629cf15326ea1f5937cd73`;
- accepted migrations V1 through V11 remained unchanged.

The documentation-only commit that promotes this ADR from `DESIGNED` to `TESTED`
is intentionally not treated as the reviewed implementation checkpoint. Its new
HEAD remains subject to fresh Branch Policy, CI, Platform CI and Codex review
before merge.

Executable evidence satisfied by the reviewed implementation checkpoint includes:

- `authorization` is a distinct Spring Modulith module;
- module dependencies remain acyclic;
- User remains authentication-provider neutral;
- TenantMembership still contains no roles/permissions;
- existing OH-009/OH-010 identity/membership tests remain green;
- trusted actor context carries internal user + trusted Tenant without leaking
  JWT/framework types into business modules;
- Permission catalog accepts only known system permissions;
- unknown permissions fail closed;
- RoleDefinition cannot contain permissions outside its permitted envelope;
- protected/system role definitions reject unauthorized mutation semantics;
- RoleAssignment is Tenant/scope bound;
- a role assignment from Tenant A cannot authorize Tenant B;
- STAFF and CUSTOMER persona semantics cannot leak authority into each other;
- effective authorization is deny-by-default;
- eligible role permission can ALLOW;
- explicit DENY override removes permission;
- ALLOW override outside the effective envelope is rejected/fails closed;
- delegation/self-escalation constraints are executable;
- SoD conflict representation fails closed;
- V12 reconstructs authorization schema/catalog from an empty real PostgreSQL
  database;
- V1 through V11 remain unchanged;
- database constraints reject duplicate/invalid authorization state;
- PostgreSQL repository behavior and exception translation are tested;
- cross-Tenant isolation has real PostgreSQL acceptance evidence;
- applicable concurrent privilege invariants have adversarial evidence;
- no unbounded identity/PII metric labels are introduced;
- privacy-safe authorization failures expose no inaccessible identity/resource
  details;
- `git diff --check` passes;
- full `mvnw clean verify` passes;
- required pull-request workflows passed on reviewed implementation checkpoint
  `a4b4f64d371292889bf0515202b6d831ca474cf5`;
- Codex review of that implementation checkpoint produced no unresolved
  irregularity;
- the subsequent documentation-only ADR promotion HEAD remains independently
  subject to fresh workflow and Codex merge gates.

## Explicitly deferred

OH-013 does not complete:

- full workforce administration;
- concrete Department/JobPosition taxonomy and supervisor hierarchy;
- promotion/demotion HTTP workflows;
- complete Tenant custom-role management UI/API;
- CustomerProfile self-service APIs;
- customer Order ownership/actions;
- identity invitation/link/unlink/relink operational lifecycle;
- employee/customer analytical scoring;
- analytical warehouse/lake technology;
- Network/Organization implementation;
- Platform administration;
- Catalog/Inventory administrative endpoints;
- Inventory receipt/adjustment/policy APIs;
- OpenAPI maturity work;
- JIT privileged access/access reviews;
- transactional outbox;
- external integrations/webhooks.

These concerns remain explicitly recorded in `docs/ROADMAP.md`.

## References

The design is informed by, but does not claim certification against:

- NIST role-based access-control work, including role hierarchy and separation of
  duties: https://csrc.nist.gov/projects/role-based-access-control
- NIST SP 800-162, Attribute Based Access Control (ABAC):
  https://csrc.nist.gov/pubs/sp/800/162/upd2/final
- NIST SP 800-205, Attribute Considerations for Access Control Systems:
  https://csrc.nist.gov/pubs/sp/800/205/final
- OWASP Authorization Cheat Sheet, especially least privilege, deny by default
  and validation on protected requests:
  https://cheatsheetseries.owasp.org/cheatsheets/Authorization_Cheat_Sheet.html
- Lei nº 13.709/2018 (LGPD), especially principles of purpose, adequacy,
  necessity, prevention and non-discrimination;
- ANPD guidance on necessity/minimization and RIPD/high-risk processing:
  https://www.gov.br/anpd/

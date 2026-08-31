# ADR-0007 — Internal User Identity and Tenant Membership

Status: TESTED

## Context

OrderHub now owns Tenant lifecycle through the `tenants` module, while Orders
continues to carry tenant identity without owning Tenant state.

The application still has no durable concept representing an individual system
user or the relationship between that user and one or more Tenants.

Authentication and authorization require those concepts eventually, but
introducing credentials, identity-provider subjects, roles or permissions before
the core identity boundary exists would couple the business model to security
mechanisms prematurely.

The next step therefore needs to establish:

1. an authentication-neutral internal User identity;
2. a durable association between a User and a Tenant;
3. module ownership for those concepts;
4. persistence boundaries that do not couple Users directly to Tenant internals.

## Decision

Introduce `users` as a first-class Spring Modulith application module parallel
to `orders` and `tenants`.

The `users` module owns:

- the User domain model;
- TenantMembership;
- User creation;
- membership establishment and lookup;
- application-owned persistence ports;
- PostgreSQL persistence implementations;
- reconstruction of persisted User and membership state;
- Spring composition for those capabilities.

## Internal User Identity

A User is identified by an opaque UUID generated internally by OrderHub.

Initial User state:

- `id`: required UUID.

OH-009 deliberately introduces no additional User attributes.

In particular, User does not contain:

- email;
- username;
- password or password hash;
- phone number;
- display name;
- OAuth/OIDC subject;
- identity-provider identifier;
- authentication status;
- roles or permissions.

The internal UUID represents durable application identity rather than a login
credential.

Future authentication mechanisms may associate external credential identities
with this internal identifier without changing User identity itself.

## Tenant Membership

TenantMembership represents association between one User and one Tenant.

Its state is:

- `userId`: required UUID;
- `tenantId`: required UUID.

The pair `(tenantId, userId)` must be unique.

A persisted membership must reference an existing internal User.

Because User and TenantMembership are both owned by the `users` module, this
invariant is enforced durably through a same-module database foreign key from
`users.tenant_memberships.user_id` to `users.users.id`.

Membership represents only the fact that a User belongs to a Tenant.

It does not express authorization.

Roles, permissions, ownership and other authorization concepts remain outside
OH-009.

## Module Boundary

The `users` module stores Tenant identity as UUID only.

It must not:

- import Tenant domain classes;
- import Tenant persistence classes;
- query `tenants.tenants` directly;
- own Tenant lifecycle.

No database foreign key is created between the `users` and `tenants` schemas.

If a future use case requires proving that a Tenant exists before establishing
membership, that interaction will occur through an explicit module/application
contract rather than shared persistence.

## Persistence

The module receives its own PostgreSQL schema:

`users`

The initial Users schema is introduced through Flyway V3.

Accepted migrations remain immutable:

- V1: Orders;
- V2: Tenants;
- V3: initial Users schema.

The User-membership referential-integrity constraint is introduced through V4.

Once V4 is accepted/shared, subsequent schema changes must use V5 or later.

Persistence continues to use Spring JDBC with explicit SQL.

Domain and application layers remain independent of JDBC, Spring persistence
types, Flyway and PostgreSQL.

Infrastructure exceptions are translated to stable application-boundary
exceptions.

## Transaction Boundary

User creation is initially a single-row persistence operation.

Membership creation is also initially a single-row persistence operation.

No generalized UnitOfWork or cross-module transaction abstraction is introduced.

If a later use case requires User creation and membership establishment to form
one atomic business operation, its transaction boundary will be designed from
that concrete requirement rather than speculated here.

## Security Boundary

OH-009 does not implement authentication or authorization.

Specifically, it introduces no:

- login/logout;
- passwords;
- JWT;
- OAuth2;
- OIDC;
- sessions;
- roles;
- permissions;
- RBAC;
- ABAC;
- Orders authorization.

`X-Tenant-Id` remains untrusted request context.

Membership existence alone must not be interpreted as proof that the current
HTTP caller owns that User identity.

## Data Minimization

The initial User model stores only an opaque UUID.

TenantMembership stores only the UUIDs necessary to represent the association.

No contact information or credential identifier is collected in this phase.

This reduces unnecessary personal-data processing, but does not by itself
constitute or prove LGPD compliance.

## Alternatives Rejected

### Use email as User identity

Rejected because it couples internal identity to personal/contact data and to a
potential authentication mechanism.

Email can change and may later participate in several authentication flows.

### Store an OIDC/OAuth subject directly on User

Rejected because no identity provider has been selected and doing so would make
the core model provider-aware prematurely.

### Put membership inside the Tenants module

Rejected because Tenant lifecycle and user identity/membership lifecycle are
different responsibilities.

### Add roles to TenantMembership now

Rejected because membership and authorization answer different questions.

Roles will be introduced only when authorization requirements are concrete.

### Add a cross-schema foreign key to tenants

Rejected because it creates database-level coupling between independently owned
application modules.

Cross-module consistency will use explicit module contracts when required.

## Consequences

Positive:

- stable internal User identity independent of authentication technology;
- minimal personal-data footprint;
- explicit ownership of User/Tenant relationships;
- authentication can evolve without changing core User identity;
- Users and Tenants remain independently owned modules.
- durable prevention of orphan memberships referencing non-existing internal Users;

Trade-offs:

- a membership row can theoretically reference a Tenant UUID that does not
  exist if persistence is bypassed;
- User initially contains little information beyond identity;
- tenant-existence consistency must later be enforced through an explicit
  module interaction when a concrete creation workflow requires it.

These trade-offs are intentional to avoid hidden cross-module coupling.

## Verification

ADR-0007 becomes TESTED only after evidence proves:

- `users` is detected as a distinct Spring Modulith module;
- User invariants are unit-tested;
- TenantMembership invariants are unit-tested;
- duplicate membership is rejected;
- application services remain Spring-independent;
- V1 through V4 reconstruct the database from an empty real PostgreSQL database;
- V1, V2 and V3 remain unchanged;
- membership persistence rejects references to non-existing internal Users;
- the User-reference foreign key remains internal to the `users` module;
- no foreign key is introduced from `users` to the `tenants` schema;
- PostgreSQL adapters persist and reconstruct state correctly;
- persistence exceptions are sanitized at the application boundary;
- Users imports no Tenant domain/persistence internals;
- no public user/membership HTTP endpoint is introduced;
- existing Orders and Tenants tests remain green;
- `git diff --check` passes;
- `mvnw clean verify` passes;
- repository-required CI checks pass.

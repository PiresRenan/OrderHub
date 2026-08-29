# ADR-0006 — Tenant Module and Ownership Boundaries

Status: DESIGNED

## Context

OrderHub is a Spring modular monolith whose first business module, Orders,
already persists durable tenant-scoped Orders in PostgreSQL.

Orders currently carries a `tenantId` as part of aggregate identity and request
context, but OrderHub does not yet own a first-class Tenant concept.

The current HTTP `X-Tenant-Id` value is untrusted request context. It is not an
authentication or authorization boundary.

Before users, tenant membership and authenticated tenant context can be
introduced, the core needs a module that explicitly owns Tenant lifecycle and
persistence.

Tenant ownership must not be assigned to Orders merely because Orders already
stores a tenant identifier.

## Decision

OrderHub will introduce `tenants` as a first-class root module alongside
`orders`.

Conceptually:

    orderhub
        orders
        tenants

The Tenants module owns:

- the Tenant aggregate;
- Tenant creation rules;
- Tenant persistence contracts;
- Tenant persistence implementation;
- reconstruction of persisted Tenants;
- future module-facing Tenant lookup contracts.

Orders must not own or persist Tenant state.

## Initial Tenant Model

OH-008 deliberately introduces a minimal Tenant aggregate.

Initial state:

- `id`: UUID;
- `name`: String, normalized and limited to 120 Unicode code points after trimming.

The aggregate must reject:

- null identifiers;
- null names;
- blank names;
- names longer than 120 Unicode code points after normalization.

Names are normalized by trimming surrounding whitespace before becoming domain
state.

No tenant status, suspension state, billing data, address, tax identifier,
contact information or authentication configuration is introduced in OH-008.

Those concepts require concrete use cases before entering the model.

## Module Boundary

The `tenants` package is a Spring Modulith root application module.

Its internal domain and persistence implementation are not public integration
contracts for other modules.

Future modules that require Tenant information must communicate through an
explicit application/module API owned by Tenants.

Direct imports such as:

    orders -> tenants.persistence
    orders -> tenants.domain.internal

are not valid module interaction.

## Orders Relationship

OH-008 does not modify Orders to validate Tenant existence.

The existing `tenantId` remains part of the Orders aggregate and persistence
identity, but remains untrusted request context at the HTTP boundary.

A later authenticated use case will connect Orders to an authenticated Tenant
context through a module/application contract.

OH-008 therefore does not claim tenant authorization or tenant isolation at the
security boundary.

## Database Ownership

Tenants owns a dedicated PostgreSQL schema:

    tenants

The first Tenant table belongs exclusively to that schema.

Orders continues owning:

    orders

Tenants owns:

    tenants

Database tables are implementation details of their owning modules.

## Cross-Module Foreign Keys

No database foreign key from `orders` tables to `tenants` tables is introduced.

Although PostgreSQL supports cross-schema foreign keys, using one here would
create a storage-level dependency between module internals.

Business/module consistency between Orders and Tenants must be established
through explicit module contracts when a concrete use case requires it.

This decision can be revisited if measured correctness requirements justify a
database-level invariant that cannot be represented adequately at the module
boundary.

## Persistence Technology

Tenants will follow the existing durable persistence baseline:

- PostgreSQL;
- Spring JDBC;
- explicit SQL;
- Flyway;
- Testcontainers with real PostgreSQL.

JPA/Hibernate is not introduced.

The Tenant domain and application layers must not import JDBC, PostgreSQL,
Flyway or Spring transaction APIs.

## Migration

The accepted Orders migration V1 is immutable.

Tenant persistence is introduced through:

    V2__create_tenants_schema.sql

V2 creates the dedicated `tenants` schema and Tenant relational structure.

Once accepted and shared, V2 must not be edited. Later corrections require a
forward migration.

## Transaction Boundary

Initial Tenant creation persists one aggregate root represented by one
relational row.

No application-level Unit of Work or generalized transaction abstraction is
introduced.

The PostgreSQL adapter remains responsible for its persistence operation.

A broader transaction abstraction requires a concrete use case coordinating
multiple independent output ports atomically.

## Persistence Failure Boundary

Spring JDBC, transaction and PostgreSQL exception types must not escape the
persistence adapter.

Infrastructure-specific failures are translated into an application-owned
Tenant persistence exception.

Public exception messages must remain stable and must not contain:

- SQL;
- JDBC URLs;
- credentials;
- tenant identifiers;
- PostgreSQL implementation details;
- stack traces.

## HTTP Boundary

OH-008 intentionally introduces no public Tenant administration HTTP endpoint.

Creating or administering tenants before authentication and authorization exist
would expose an administrative capability without a security boundary.

The Tenant application use case will exist and be executable through tests and
future authenticated adapters.

HTTP administration is deferred to the identity/security phase.

## Privacy and Data Minimization

OH-008 introduces no contact information or user information.

Tenant name is the only descriptive field introduced.

A tenant name may still identify a natural person in some business contexts, so
logs and errors must not unnecessarily expose it.

Synthetic Tenant data must be used in automated tests.

This ADR does not claim legal LGPD compliance.

## Rejected Alternatives

### Put Tenant inside Orders

Rejected because Tenant lifecycle is not an Orders responsibility.

### Share Tenant persistence entities with Orders

Rejected because persistence models are module implementation details.

### Add cross-schema foreign key immediately

Rejected because no concrete current use case requires storage-level coupling.

### Add public Tenant HTTP administration now

Rejected because authentication and authorization do not yet exist.

### Add status, plans, billing or organization metadata now

Rejected because these fields currently have no concrete behavioral requirement.

## Verification Required Before TESTED

ADR-0006 remains DESIGNED until evidence proves:

- `tenants` exists as a separate Spring Modulith root module;
- module verification remains green;
- Tenant domain invariants are tested;
- CreateTenant orchestration is tested independently of Spring;
- V1 remains unchanged;
- V2 builds the Tenant schema from an empty PostgreSQL database;
- PostgreSQL persistence uses a real Testcontainers instance;
- Tenant persistence and reconstruction work correctly;
- database constraints reject invalid stored state;
- persistence failures do not expose infrastructure implementation details;
- Orders does not import Tenant persistence/domain implementation classes;
- existing Orders tests remain green;
- `git diff --check` succeeds;
- `mvnw clean verify` succeeds;
- required CI and platform checks succeed.

Only after that evidence exists may ADR-0006 become TESTED.

## Follow-up Decisions

Subsequent work may introduce:

- users;
- user-to-tenant membership;
- authenticated tenant context;
- roles and permissions;
- secured Tenant administration;
- Orders admission based on authenticated Tenant context.

Those decisions are deliberately outside OH-008.

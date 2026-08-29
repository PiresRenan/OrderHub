# ADR-0005 — PostgreSQL Persistence and Transaction Boundaries

Status: DESIGNED

## Context

OrderHub currently persists Orders through an in-memory implementation of the
`OrderRepository` output port.

This adapter was sufficient for the initial vertical slice but provides no
durability, does not share state across application replicas and cannot provide
database-level transactional or concurrency guarantees.

OH-006 established the containerized runtime and multi-replica platform required
to introduce shared durable state without installing infrastructure services
directly on the development host.

OH-007 introduces PostgreSQL persistence for the Orders aggregate while
preserving the existing hexagonal architecture.

The current application flow is:

HTTP adapter
    -> CreateOrderUseCase
        -> CreateOrderService
            -> OrderRepository
            -> OrderIdGenerator

`CreateOrderService` contains no Spring or persistence-framework dependency.
`OrderRepository.save(Order)` already represents persistence of the complete
Order aggregate rather than individual database-table operations.

The persistence design must therefore preserve that semantic boundary.

## Decision

PostgreSQL will become the durable persistence engine for Orders.

The initial implementation will use:

- PostgreSQL 18.6;
- Spring JDBC with explicit SQL;
- Flyway for schema migrations;
- Testcontainers with a real PostgreSQL engine for integration verification;
- programmatic transaction demarcation inside the PostgreSQL persistence
  adapter;
- the existing application-owned `OrderRepository` port.

JPA and Hibernate will not be introduced in OH-007.

## PostgreSQL Image

The committed PostgreSQL runtime reference is:

`postgres:18.6-trixie@sha256:4ef4dbc939d61acea57712655ddb4b4ab27419c913f94cca0cd57cb3ea3c2280`

This digest identifies the immutable multi-platform OCI image index observed
during OH-007 design.

For the current `linux/amd64` development and CI environment, that index
resolves to:

`sha256:7341002d2b8c7c5bdd7542a671a95b36196c0b5b888daf454ae4fc33ba5346d7`

The human-readable version and distribution remain in the image reference so
dependency intent remains visible while the digest prevents silent tag drift.

Floating references such as `postgres:latest`, `postgres:18` or
`postgres:18.6-trixie` without a digest must not be used in committed runtime
or integration-test definitions.

## Persistence Technology

Spring JDBC will be used instead of JPA/Hibernate for the first durable Orders
adapter.

The adapter will own explicit SQL for:

- inserting the Order root;
- inserting Order items;
- loading the Order root;
- loading Order items;
- reconstructing the aggregate.

This keeps relational behavior visible and allows database constraints,
transaction boundaries and query semantics to be evaluated directly.

The domain and application layers must not import:

- JDBC APIs;
- PostgreSQL driver types;
- Flyway APIs;
- Spring transaction APIs;
- persistence entity classes.

## Aggregate Persistence Contract

`OrderRepository.save(Order)` represents persistence of one complete Order
aggregate.

For the PostgreSQL implementation this contract is atomic.

The following operations:

1. insert the Order root;
2. insert every Order item;

must execute within the same database transaction.

Successful completion commits the complete aggregate.

Failure of any root or item write must roll back every write performed for that
aggregate.

A partially persisted Order is invalid storage state.

## Transaction Boundary

The transaction boundary for OH-007 belongs to the PostgreSQL repository
adapter.

The application service will not receive `@Transactional`.

The initial implementation will use Spring's programmatic transaction support,
through `TransactionTemplate` / `TransactionOperations`, inside infrastructure
code.

Conceptually:

CreateOrderService
    -> OrderRepository.save(order)
        -> transaction begin
            -> INSERT Order
            -> INSERT item 1
            -> INSERT item 2
            -> ...
        -> commit

On failure:

CreateOrderService
    -> OrderRepository.save(order)
        -> transaction begin
            -> INSERT Order
            -> INSERT item 1
            -> INSERT item N fails
        -> rollback

This keeps the existing application service independent of Spring while making
the aggregate persistence contract explicit.

## Rejected Transaction Alternative — Application `@Transactional`

Adding `@Transactional` to `CreateOrderService.create(...)` is rejected for
OH-007.

Although technically valid, it would introduce a Spring transaction concern
directly into an application service that currently depends only on application
ports and domain types.

The required transaction currently exists entirely inside one aggregate
persistence operation, so framework transaction semantics do not need to cross
the application boundary.

## Deferred Transaction Alternative — Unit of Work Port

A framework-neutral `TransactionRunner`, `UnitOfWork` or equivalent application
port is not introduced in OH-007.

There is currently only one transactional persistence operation.

Such an abstraction becomes meaningful when one application use case must
atomically coordinate multiple independent output ports, for example:

- Order persistence;
- transactional outbox persistence.

At that point the transaction boundary must be reconsidered rather than
extending the repository-owned transaction accidentally.

## Database Schema Ownership

Orders will own a dedicated PostgreSQL schema named:

`orders`

The initial persistence model contains:

- `orders.orders`;
- `orders.order_items`.

Tables owned by other future modules must not be created inside this schema.

## Order Root

`orders.orders` will persist:

- `id`;
- `tenant_id`;
- `customer_id`;
- `status`.

The current domain contains no monetary values, timestamps or descriptive
customer/product data, so OH-007 will not invent persistence columns for them.

Identifiers will use PostgreSQL native `uuid`.

The persistence identity of an Order is the pair:

- `tenant_id`;
- `id`.

The root table therefore uses the composite primary key:

`PRIMARY KEY (tenant_id, id)`

This makes tenant ownership part of the relational identity instead of relying
only on query conventions.

The domain `Order.id` remains a UUID aggregate identifier, but persisted
relationships and lookups must scope that identifier by tenant.

## Multi-Tenant Persistence Boundary

Persistence access must remain tenant-scoped.

The repository read contract will use both:

- `tenantId`;
- `orderId`.

A generic persistence method that retrieves an Order solely by `orderId` will
not be exposed by the application port in OH-007.

`tenant_id` will also be propagated to persisted order-item rows.

The child table will use a composite foreign-key relationship containing both
`tenant_id` and `order_id`.

This deliberately duplicates the tenant identifier into the child relation so
the database can enforce that an item belongs to the same tenant as its Order
root.

The adapter derives the item tenant identifier from the owning aggregate; this
does not add tenant state to the `OrderItem` domain type.

This constraint is defense in depth and does not replace future authorization
or Row-Level Security decisions.

## Order Items

`orders.order_items` will persist:

- `tenant_id`;
- `order_id`;
- `line_number`;
- `product_id`;
- `quantity`.

`line_number` preserves the ordering semantics of `Order.items()`, which is a
List.

The database must not infer item uniqueness from `product_id`, because the
domain currently does not prohibit multiple lines referencing the same
product.

No `UNIQUE(order_id, product_id)` constraint will therefore be introduced.

The item identity inside persistence is the owning Order plus its line number.

The child table therefore uses the composite primary key:

`PRIMARY KEY (tenant_id, order_id, line_number)`

and references the owning root through:

`FOREIGN KEY (tenant_id, order_id) REFERENCES orders.orders (tenant_id, id)`

This prevents an item row from being relationally attached to an Order under a
different tenant.

## Database Constraints

The initial schema will enforce at least:

### Order

- non-null `id`;
- non-null `tenant_id`;
- non-null `customer_id`;
- non-null `status`;
- composite primary-key uniqueness across `tenant_id` and `id`;
- status restricted to domain-supported persisted values.

### Order item

- non-null tenant identifier;
- non-null Order identifier;
- non-null product identifier;
- positive quantity;
- valid line number;
- unique line position inside one Order;
- foreign-key ownership by the matching tenant and Order.

Database constraints are persistence correctness controls.

They complement domain validation and protect storage when application
validation is bypassed by defects, migrations, administrative operations or
future adapters.

## Foreign-Key Ownership

Order items are owned by the Order aggregate.

Their foreign key will therefore use cascading deletion from the Order root.

This represents aggregate ownership only.

OH-007 does not add a business operation that deletes Orders.

No foreign keys will be created to Customer or Product because those tables do
not currently belong to the Orders persistence model.

## Status Representation

Order status will initially be persisted as text with an explicit database
check constraint.

A PostgreSQL enum is not introduced.

Adding a new domain status requires a deliberate Flyway migration updating the
database constraint.

This keeps status evolution visible in schema history rather than coupling the
domain directly to a PostgreSQL-specific enum type.

## Money

Monetary persistence is deferred.

The current Order aggregate contains no price, subtotal, total, tax or currency
state.

OH-007 must not invent a money representation in anticipation of future domain
requirements.

A future requirement involving monetary values must decide amount precision,
currency and arithmetic semantics before adding schema columns.

## Domain Rehydration

`Order.create(...)` represents creation of a new Order and currently produces
the CREATED lifecycle state.

Loading persisted state is a different operation.

The domain will therefore gain an explicit reconstruction factory, expected to
have semantics equivalent to:

`Order.rehydrate(...)`

The factory will reconstruct already-valid persisted aggregate state without
pretending that a new business Order is being created.

Persistence adapters must not use reflection, persistence entities or framework
mechanisms to mutate private domain state.

## Repository Read Contract

The repository will gain a tenant-scoped read operation equivalent to:

`findById(UUID tenantId, UUID orderId)`

The return type will express absence rather than using `null`.

Root and item queries must reconstruct a single immutable Order aggregate.

Persistence DTOs or JDBC row structures must remain inside the outgoing adapter.

## Flyway

Flyway is the sole schema migration mechanism for OH-007.

The initial migration will create the Orders relational model from an empty
PostgreSQL database.

Accepted migration files are immutable.

Once a migration has been shared and accepted, schema changes must be
introduced through new forward migrations rather than editing the existing
migration.

Application startup must not use Hibernate schema generation, ad-hoc SQL
initialization or manual host-side database scripts as competing schema
authorities.

## Integration Testing

Persistence integration tests will run against PostgreSQL through Testcontainers.

H2 and mocked JDBC behavior are rejected for persistence acceptance tests.

The test PostgreSQL image must use the same pinned PostgreSQL 18.6 image family
defined by this ADR.

Integration tests must verify at minimum:

- Flyway migration from an empty database;
- complete aggregate insertion;
- multi-item persistence;
- tenant-scoped read-back;
- correct domain reconstruction;
- database constraint enforcement;
- rollback when an item write fails after the root has already been inserted;
- absence of partial persisted state after rollback.

Test data must be synthetic.

## Testcontainers Version Management

The project uses Spring Boot dependency management.

The Testcontainers dependencies used by OH-007 should therefore use the
versions managed by the current Spring Boot release rather than defining a
second independent Testcontainers version property unless a demonstrated
compatibility problem requires an override.

## Local Development

Docker Compose will gain PostgreSQL as an infrastructure service.

PostgreSQL will not be installed directly on the Windows host.

Local development credentials must be synthetic and externally configurable.

No production credential, token or password may be committed.

Persistent local database state may use an explicitly named Docker volume.

Volume deletion must remain deliberate and must not be part of normal
application shutdown.

## Kubernetes Scope

OH-007 does not deploy PostgreSQL into the current kind Kubernetes manifests.

The existing Kubernetes profiles model the application workload, not a
production database topology.

Running a single unmanaged PostgreSQL Pod merely to make the manifests appear
complete would create misleading availability semantics.

Database orchestration, HA and production topology require their own explicit
decision.

## Health Semantics

After a DataSource exists, database availability will participate in application
readiness.

A replica unable to use its required database should not receive new
application traffic.

Database availability must not participate in process liveness.

Restarting a healthy JVM cannot repair an unavailable shared database, and
placing database health in liveness could cause cascading restarts during a
database incident.

Health responses must continue to avoid exposing database addresses,
credentials, SQL details or internal exception messages.

## Error Boundary

SQL text, SQLSTATE details, constraint names, JDBC URLs, credentials, stack
traces and internal database exception messages must not be exposed through
normal HTTP responses.

Infrastructure exceptions may be translated into application-safe failures when
required.

Normal logs must not emit request bodies, credentials, customer identifiers or
other unnecessary sensitive values merely because persistence failed.

## LGPD Boundary

`customer_id` can constitute personal data when it can be linked to an
identifiable natural person.

It already exists as required Order domain state and is persisted for that
existing purpose.

OH-007 introduces no additional customer name, document, email, phone, address
or unrelated personal-data field.

Database persistence alone does not establish LGPD compliance.

## Concurrency Boundary

OH-007 establishes shared durable state but does not claim that all future
business concurrency problems are solved.

Process-local synchronization must not be used for invariants shared across
application replicas.

Future concurrency requirements should prefer appropriate mechanisms such as:

- database uniqueness constraints;
- atomic SQL operations;
- transaction isolation;
- targeted row locking;
- durable idempotency;
- bounded retry policies.

Global SERIALIZABLE isolation is not introduced by this ADR.

## Consequences

Orders gain durable state shared between application replicas.

The persistence adapter becomes responsible for relational mapping and atomic
aggregate storage.

The application and domain layers remain independent of PostgreSQL, JDBC,
Flyway and Spring transaction annotations.

Explicit SQL increases implementation responsibility but makes schema,
constraint and transaction behavior directly visible and testable.

A future use case requiring atomic coordination across multiple output ports
will require re-evaluation of the repository-owned transaction boundary.

## Verification Required Before TESTED

ADR-0005 remains DESIGNED until evidence proves:

- PostgreSQL 18.6 starts using the pinned image;
- Flyway creates the complete Orders schema from an empty database;
- existing application tests remain green;
- PostgreSQL adapter tests use a real Testcontainers PostgreSQL instance;
- one Order with multiple items commits atomically;
- forced item failure rolls back the root and all item writes;
- persisted state can be rehydrated into the domain;
- tenant-scoped lookup cannot return an Order belonging to another tenant;
- database constraints reject invalid persisted state;
- Compose starts the application and PostgreSQL using synthetic configuration;
- readiness reacts correctly to database availability;
- liveness remains process-oriented;
- no database implementation details leak through HTTP error responses;
- `git diff --check` succeeds;
- `mvnw clean verify` succeeds;
- required CI and platform checks succeed.

Only after this evidence exists may ADR-0005 become TESTED.

## Follow-up Decisions

Later work may define:

- database concurrency and retry policy;
- connection-pool sizing and connection-budget mathematics;
- PgBouncer;
- durable idempotency;
- transactional outbox;
- PostgreSQL production topology and high availability;
- backup and restore policy;
- Row-Level Security;
- load and horizontal-scaling benchmarks.

## References

- PostgreSQL 18 documentation
- Docker Official Image for PostgreSQL
- Spring Framework JDBC documentation
- Spring Framework transaction-management documentation
- Spring Boot 4.1.1 dependency-management documentation
- Flyway PostgreSQL documentation
- Testcontainers PostgreSQL documentation
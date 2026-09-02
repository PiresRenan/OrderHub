# OrderHub Engineering Roadmap

This roadmap records the current engineering direction for OrderHub after OH-010.
It is an architectural planning baseline, not a promise to introduce a technology
before a concrete business or operational problem justifies it.

## Decision standard

OrderHub optimizes for defensible engineering decisions rather than minimum code
or maximum technology count.

For every structural evolution, evaluate at least:

- business correctness and financial-loss prevention;
- tenant isolation and privacy;
- authentication and authorization;
- consistency and concurrency;
- failure recovery and idempotency;
- performance and resource consumption;
- availability and graceful degradation;
- horizontal scalability;
- observability and auditability;
- maintainability and modular ownership;
- testability under normal, concurrent and failure conditions;
- operational complexity and rollback cost.

The preferred solution is the smallest one that robustly satisfies the proven
requirements. A simpler design is not automatically better, and a more complex
design is not automatically more professional.

New frameworks, brokers, databases, services and architectural patterns require
an explicit problem, hypothesis and verification plan.

## S1 — Reliable Modular Core

The S1 business flow remains:

```text
trusted tenant context
-> validate request
-> check durable idempotency contract
-> transaction
    -> create order
    -> commit inventory atomically
    -> persist durable idempotency state
    -> persist durable event/outbox state
-> commit
-> response
```

OH-010 completed authenticated internal identity and trusted tenant context.
The remaining S1 work is deliberately sequenced so each correctness boundary can
be proven before adding asynchronous distribution.

## OH-011 — Tenant-scoped catalog, inventory and atomic order commitment

Status: COMPLETED — merged into `pre-release` at `93c004f82e11e61885ffc969b6a8d1eb9e901a24` on 2026-09-01

Introduce first-class `catalog` and `inventory` modules.

Catalog direction:

- Product is a merchandising/root concept;
- ProductVariant is the concrete sellable unit/SKU;
- Inventory tracks ProductVariant, not abstract Product;
- tenant-scoped Category trees replace fixed group/subgroup fields;
- Products may belong to multiple Categories;
- required commercial identity is separated from optional descriptive metadata;
- standardized identifiers such as GTIN/MPN are optional because legitimate
  products may not possess them;
- price is a dedicated monetary concept with explicit currency and decimal-safe
  representation, not binary floating point;
- pricing structure must allow later B2B scopes without implementing a complete
  pricing engine in OH-011;
- media is modeled as product/variant metadata/reference; arbitrary remote media
  is not fetched server-side in OH-011.

Inventory direction:

- tenant + variant scoped positions;
- `onHand`, `committed`, `backordered`, `safetyStock` are distinct quantities;
- physical stock never becomes negative;
- tenant oversell policy initially supports `DENY` and `ALLOW_BACKORDER`;
- missing inventory/policy fails closed;
- InventoryCommitment provides durable Order-to-Variant allocation traceability;
- multi-item commitment is atomic with Order persistence;
- correctness relies on PostgreSQL transactions/atomic mutations rather than
  process-local locks;
- concurrency, lock timeout, deadlock prevention, cross-tenant isolation and
  multi-replica behavior are required acceptance evidence.

Temporary reservation/TTL, warehouse sourcing, stock transfer, complete PIM,
full pricing, administrative APIs and order cancellation remain separate
problems.

## OH-012 — Durable order request idempotency and recovery

Status: IMPLEMENTED — local verification complete; PR/remote validation pending

OH-011 made Order persistence, Catalog orderability validation and Inventory
commitment one atomic business transaction. The next exposed reliability gap is
an ambiguous client outcome after that transaction commits.

`POST /orders` therefore gains durable request idempotency before automatic
retries, transactional outbox or external integrations are introduced.

Direction:

- require one client-supplied `Idempotency-Key` for Order creation;
- scope key identity by trusted Tenant and versioned operation;
- persist only a cryptographic digest of the raw key;
- compare a canonical business-command fingerprint rather than raw JSON bytes;
- preserve current Order item sequence/multiplicity semantics in the fingerprint;
- coordinate duplicate requests using PostgreSQL uniqueness/transaction semantics;
- commit the successful idempotency outcome atomically with Order and Inventory;
- replay a completed successful result without repeating business effects;
- reject a completed key reused for a different fingerprint;
- bound only idempotency acquisition lock waiting through a dedicated externally
  configured PostgreSQL `lock_timeout`, restoring the prior value before
  Catalog/Inventory work;
- retain completed records without automatic expiry in the initial implementation;
- never expose raw idempotency keys or fingerprints through logs, metrics or
  Problem Details.

The latest IETF HTTPAPI `Idempotency-Key` Internet-Draft is treated only as design
precedent. Revision 07 expired on 2026-04-18 and is not an active RFC; OrderHub
therefore owns and documents its concrete public contract explicitly.

OH-012 does not introduce Redis, brokers, distributed mutex products, broad
automatic retries, outbox publication or authorization administration.
## Authorization foundation — planned after a concrete privileged surface exists

Inventory and Catalog administration create the first concrete requirement for
fine-grained authorization beyond tenant membership.

The long-term authorization model must support multiple scopes:

```text
PLATFORM
  -> NETWORK / ORGANIZATION
      -> TENANT
          -> RESOURCE
```

Authentication remains responsible only for identity. Authorization decides what
an authenticated principal may do to a resource in an effective scope.

Direction:

- deny by default;
- least privilege;
- permission checks on every protected request;
- predefined roles as reusable permission sets;
- tenant-defined custom roles where justified;
- role assignment is scoped, not globally attached to User;
- users may hold different roles in different tenants/networks;
- delegated administrators may grant only permissions within their delegation
  boundary;
- privileged-role assignment is distinct from ordinary user management;
- separation of duties is required for high-impact operations;
- authorization architecture must remain compatible with future relationship-
  based rules if Platform -> Network -> Tenant -> Resource inheritance makes
  flat RBAC insufficient.

## Inventory and Catalog administration — planned

After authorization exists, expose controlled administration capabilities such
as:

- create/update catalog products and variants;
- category management;
- pricing/base-price management;
- inventory receipt and adjustment;
- safety-stock management;
- inventory policy management;
- user administration within permitted scope.

Inventory changes are represented as auditable movements/adjustments rather than
untraceable `set quantity` operations.

Administrative write operations require explicit permissions and must produce
an audit trail.

## API documentation / OpenAPI — planned with administration/API maturity

OpenAPI support may exist in code promoted to `main`; exposure is controlled by
runtime environment, not by keeping code out of the main branch.

Intended runtime posture:

- local/development: OpenAPI document and Swagger UI enabled;
- pre-release/staging: enabled only when required and protected by authentication;
- production: interactive Swagger UI disabled and runtime API-document endpoint
  disabled unless an explicit operational/product requirement later changes the
  decision;
- CI: generated OpenAPI contract may be validated/published as an artifact.

Production bearer tokens must not be persisted by browser documentation tooling.

## Hierarchical administration — planned

OrderHub is expected to support three administrative experiences without
requiring three independently secured backends:

### Platform administration

Manages platform-owned lifecycle and operations such as tenant/network creation,
suspension/recovery, security administration and auditing.

Platform administrators do not automatically receive unrestricted access to
private tenant business data.

A single logical `PLATFORM_ROOT` / break-glass identity may exist for bootstrap
and catastrophic recovery, but it is not a daily administrator account. Future
controls should include strong authentication, short sessions, explicit reason,
alerts and tamper-resistant auditing.

### Network / organization administration

Represents a business operator responsible for multiple tenants, for example a
retail network containing multiple stores.

A network-scoped principal may move between authorized tenants and administer
resources within that network but cannot manage unrelated networks or platform
ownership/lifecycle.

### Tenant workspace

Tenant-scoped roles may include, subject to later design and evidence:

- Tenant Administrator;
- User Manager;
- Catalog Manager;
- Inventory Manager;
- Order Manager;
- Inventory Operator;
- Order Operator;
- Auditor;
- Customer / restricted user;
- tenant-defined custom roles.

## Privileged access and audit — planned

High-impact capabilities must be separated from ordinary administration.

Direction:

- immutable/append-oriented audit evidence for privilege changes and privileged
  operations;
- actor, effective scope, action, resource, outcome, reason and correlation
  metadata without copying unnecessary private payloads;
- support/recovery operations should be callable without granting broad read
  access to tenant data;
- later evolution may introduce just-in-time privileged activation, expiry,
  approval and periodic access review when the operational complexity warrants
  it.

## Transactional outbox — planned

Introduce durable event publication only when there is a concrete asynchronous
consumer. Database state and event publication must not become a dual write.

The implementation decision will compare a manual transactional outbox with
Spring Modulith event publication facilities using actual OrderHub requirements
and failure tests.

A message broker is not introduced merely because events exist.

## External integrations and webhooks — planned

External systems that create Orders should primarily use an authenticated,
idempotent command/API contract.

Inbound webhook adapters may be introduced for systems that naturally deliver
events.

Outbound webhooks require durable outbox/event state first.

Webhook direction:

- versioned event schema;
- delivery/event identifiers;
- HMAC-style signature with timestamp/replay protection;
- duplicate-safe consumers/delivery ledger;
- finite retries with backoff;
- manual redelivery and reconciliation;
- no assumption of exactly-once delivery or global ordering;
- privacy-safe payload minimization.

## Later domain evolution — evidence driven

Potential future capabilities include:

- temporary Inventory reservation with TTL when cart/checkout/payment flows
  justify it;
- warehouses/locations, sourcing and stock transfer;
- receiving, damaged stock and quality-control states;
- richer Product Types/attributes and PIM workflows;
- B2B price lists, customer-group/channel pricing, discounts and promotions;
- fulfillment and shipping;
- search indexing when PostgreSQL catalog discovery stops meeting measured needs;
- horizontal scaling/capacity tuning based on benchmarks rather than assumptions;
- custom-role lifecycle, JIT privileged access and access reviews.

None of these items is automatically in scope merely because it appears in this
roadmap. Each requires a concrete use case, threat/failure model, acceptance
criteria and evidence before implementation.

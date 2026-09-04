# OrderHub Engineering Roadmap

This roadmap records the current engineering direction for OrderHub after OH-012.
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
trusted actor / tenant context
-> validate request
-> check durable idempotency contract
-> transaction
    -> create order
    -> commit inventory atomically
    -> persist durable idempotency state
    -> persist durable event/outbox state when a concrete consumer exists
-> commit
-> response
```

OH-010 completed authenticated internal identity and trusted Tenant context.
OH-011 established tenant-scoped Catalog/Inventory correctness and atomic Order
commitment. OH-012 closed the ambiguous-client-outcome gap with durable request
idempotency.

### OH-011 — Tenant-scoped catalog, inventory and atomic order commitment

Status: COMPLETED — merged into `pre-release` at
`93c004f82e11e61885ffc969b6a8d1eb9e901a24` on 2026-09-01.

Catalog direction delivered:

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
- pricing structure allows later B2B scopes without prematurely implementing a
  complete pricing engine;
- media is modeled as product/variant metadata/reference; arbitrary remote media
  is not fetched server-side.

Inventory direction delivered:

- tenant + variant scoped positions;
- `onHand`, `committed`, `backordered`, `safetyStock` are distinct quantities;
- physical stock never becomes negative;
- tenant oversell policy supports `DENY` and `ALLOW_BACKORDER`;
- missing inventory/policy fails closed;
- InventoryCommitment provides durable Order-to-Variant allocation traceability;
- multi-item commitment is atomic with Order persistence;
- correctness relies on PostgreSQL transactions/atomic mutations rather than
  process-local locks;
- concurrency, bounded waits, deadlock prevention, cross-Tenant isolation and
  multi-replica behavior have executable evidence.

Temporary reservation/TTL, warehouse sourcing, stock transfer, complete PIM,
full pricing, administrative APIs and order cancellation remain separate
problems.

### OH-012 — Durable order request idempotency and recovery

Status: COMPLETED — merged into `pre-release` at
`40e0283c498ee4e575629cf15326ea1f5937cd73` on 2026-09-02.

`POST /orders` has durable request idempotency before automatic retries,
transactional outbox or external integrations are introduced.

Direction delivered:

- require one client-supplied `Idempotency-Key` for Order creation;
- scope key identity by trusted Tenant and versioned operation;
- persist only a cryptographic digest of the raw key;
- compare a canonical business-command fingerprint rather than raw JSON bytes;
- preserve Order item sequence/multiplicity semantics in the fingerprint;
- coordinate duplicate requests using PostgreSQL uniqueness/transaction semantics;
- commit the successful idempotency outcome atomically with Order and Inventory;
- replay a completed successful result without repeating business effects;
- reject a completed key reused for a different fingerprint;
- bound only idempotency acquisition waiting through a dedicated PostgreSQL
  `lock_timeout`, restoring the prior value before Catalog/Inventory work;
- retain completed records without automatic expiry in the initial implementation;
- never expose raw idempotency keys or fingerprints through logs, metrics or
  Problem Details.

The IETF HTTPAPI `Idempotency-Key` Internet-Draft is treated only as design
precedent. Revision 07 expired on 2026-04-18 and is not an active RFC; OrderHub
owns and documents its concrete public contract explicitly.

OH-012 does not introduce Redis, brokers, distributed mutex products, broad
automatic retries, outbox publication or authorization administration.

## S2 — Identity, Personas and Authorization

The next product boundary is no longer authentication alone. OrderHub now needs
to represent people acting in different capacities, organizational authority,
customer self-service relationships and fine-grained authorization without
collapsing those concerns into the core User identity.

The enduring separation is:

```text
User identity
    != Tenant membership
    != persona
    != workforce position
    != role
    != permission
    != customer/resource relationship
```

Authentication establishes who the actor is. Persona establishes the capacity
in which the actor operates. Organizational position constrains an employee's
authority ceiling. Roles group permissions. Bounded overrides customize access
inside that ceiling. Resource relationships and contextual policies may further
restrict access.

Authorization hierarchy is not blind permission inheritance.

### OH-013 — Identity personas and scoped authorization kernel

Status: COMPLETED — merged into `pre-release` at
`7344c5e1b573f3d79846719cd93aa951716c0f17` on 2026-09-02 via PR #27.

OH-013 introduces a first-class framework-neutral authorization module and the
minimum persona vocabulary required by later workforce and customer domains.

Module ownership:

- `users` retains User identity, TenantMembership and external-identity binding;
- `authorization` owns Permission, RoleDefinition, RoleAssignment, permission
  envelopes, bounded overrides, constraints and authorization decisions;
- `security` remains authentication/trusted-context infrastructure and adapts the
  authenticated internal User into trusted actor context;
- future `workforce` owns StaffProfile, departments, positions, reporting lines
  and employment/promotion lifecycle;
- future `customers` owns CustomerProfile and commercial/self-service
  relationships.

Initial personas:

- `STAFF` — organizational actor whose authority is constrained by scope,
  responsibility and role policy;
- `CUSTOMER` — consumer actor whose self-service access is primarily based on
  ownership/relationship to customer resources, not employee RBAC.

Authorization direction:

- deny by default;
- least privilege;
- explicit permission checks at protected boundaries;
- system-owned/versioned atomic permission catalog;
- predefined roles as reusable permission sets;
- Tenant custom roles may later select only allowed system permissions;
- role assignment is scope-bound, never globally attached to User;
- a User may hold different roles in different Tenants and later organizations;
- authority/position envelopes cap which permissions may be granted;
- permission customization cannot implicitly promote an employee;
- direct `ALLOW` overrides may never exceed the effective envelope;
- direct `DENY` overrides may reduce authority;
- privileged-role assignment is distinct from ordinary user management;
- delegated administrators may grant only within their delegation boundary;
- system/protected roles are not mutable through ordinary Tenant role editing;
- static/dynamic separation-of-duty constraints must be representable;
- future customer authorization must support ownership/relationship and context
  checks without pretending Customers are employee roles;
- raw JWT roles, Tenant claims or arbitrary provider claims never become the
  durable business authorization source of truth.

Tenant-facing role families to be enabled by the model include:

- Tenant Administrator;
- User Manager;
- Catalog Manager;
- Inventory Manager;
- Order Manager;
- Inventory Operator;
- Order Operator;
- Auditor;
- Restricted Staff;
- Tenant-defined custom roles within explicit envelopes.

The exact organizational position taxonomy is intentionally deferred to OH-014;
OH-013 builds the authorization ceiling/envelope mechanism without inventing a
complete workforce hierarchy prematurely.

Privacy/data posture:

- maximize analytical information density, not indiscriminate PII collection;
- personal-data processing must have explicit purpose and satisfy necessity and
  proportionality;
- operational PII is separated from analytical identity/projections;
- activity facts prefer internal/pseudonymous identifiers;
- raw JWTs, request bodies and duplicated email/name/phone/address do not become
  analytical event payloads;
- facts/events remain distinct from later derived employee-performance or
  customer-behavior scores;
- sensitive personal data is deny-by-default for analytics absent a concrete
  lawful purpose and additional safeguards;
- future automated profiling affecting professional or consumer interests must
  have explicit governance, reviewability and impact analysis.

### OH-014 — Tenant workforce administration

Status: COMPLETED — merged into `pre-release` at
`12a7e5353b13894a6b8bffe10bb9d9b34cd4b699` on 2026-09-03 via PR #29.

Build the Staff persona into a complete Tenant workforce model rather than adding
job attributes to User.

Direction:

- StaffProfile scoped to one Tenant relationship;
- departments/business functions coherent with OrderHub operations;
- concrete JobPosition catalog and Tenant-specific positions where justified;
- authority bands such as operational, supervisory, coordination, management
  and Tenant governance;
- reporting/supervisor relationships without using them as implicit access
  grants;
- position-to-permission-envelope mapping;
- predefined functional roles;
- Tenant custom-role administration inside the applicable envelope;
- bounded per-user permission overrides;
- explicit promotion/demotion rather than accidental privilege accumulation;
- position/history snapshots needed for later analysis;
- ordinary versus privileged role assignment;
- last-administrator and self-escalation protections;
- concurrency-safe privilege invariants;
- append-oriented audit evidence for membership, position, role and privilege
  changes.

An employee account must never be able to reach management/governance authority
merely by accumulating custom permissions. Promotion changes the organizational
authority ceiling; permission customization operates only inside the ceiling.

### OH-015 — Customer account and self-service

Status: COMPLETED — merged into `pre-release` at
`db7004a0df6035fa4c4fb886b4e383f4ae967f89` on 2026-09-04 via PR #32;
issue #30 closed as completed.

The completed OH-015 slice is defined by issue #30 and ADR-0013 `TESTED`.
Broader Customer capabilities listed as roadmap direction remain future work unless
admitted by a separately governed slice.

Introduce `customers` as a first-class commercial/self-service domain rather
than treating a consumer as another Staff role.

Direction:

- CustomerProfile distinct from User identity;
- one User may have Customer relationships in multiple Tenants and a Staff
  relationship elsewhere without authority leakage;
- optional binding between a commerce Customer and an authenticated User where
  guest/pre-account lifecycle requires it;
- own profile/preferences and communication settings;
- own Orders and state-dependent allowed actions;
- own addresses, returns/exchanges and other customer-owned resources as those
  domains become concrete;
- purchase history and customer lifecycle projections;
- customer privacy/consent state where a concrete processing purpose requires it;
- relationship/ownership authorization rather than employee RBAC;
- privacy-safe customer activity facts to support later cohort, retention,
  loyalty, affinity and behavior studies.

No Customer persona grants Staff permissions, even when both personas resolve to
the same internal User.

### OH-016 — Privacy-safe operational analytics foundation

Status: IN PROGRESS — issue #31; initially developed in parallel from the
integrated OH-014 baseline and synchronized on 2026-09-04 with post-OH-015
`pre-release@db7004a0df6035fa4c4fb886b4e383f4ae967f89` through merge checkpoint
`5b15baac4f0bdfc65253889eb705b7b9ac987c2f`.

The first concrete producer remains the append-oriented workforce operational
evidence already integrated by OH-014. OH-015 Customer semantics are now present
in the baseline, but synchronization does not silently expand OH-016 scope:
Customer-specific analytical facts remain outside the current executable slice
unless admitted by an explicit governed scope change.

Prepare OrderHub for serious operational, workforce and customer analytics
without converting the transactional database into an indiscriminate personal-
data warehouse.

Direction:

- explicit activity/business facts with purpose and schema ownership;
- pseudonymous/internal analytical subject keys where identification is not
  required by the analytical use case;
- separation of operational PII from analytical facts and derived projections;
- retention/deletion policy per data class and purpose;
- data classification and allowed-consumer metadata;
- employee facts such as workload, processing outcomes, SLA adherence, rework,
  approvals and operational actions when generated by actual system workflows;
- customer facts such as purchase recency/frequency/value, basket behavior,
  returns/cancellations and product/category affinity when generated by actual
  commerce workflows;
- analytical scores/models are derived, versioned artifacts rather than mutable
  truth stored on StaffProfile/CustomerProfile;
- later professional/consumer profiling must support explainability/review and
  privacy-impact governance where applicable;
- no warehouse/lake/streaming platform is selected before measured workload and
  concrete analytical consumers justify it.

## S3 — Hierarchical Administration

### OH-017 — Platform and Network / Organization administration

Status: PLANNED — depends on the scoped authorization model and concrete
organizational requirements.

OrderHub is expected to support three administrative experiences without
requiring three independently secured backends:

```text
PLATFORM
  -> NETWORK / ORGANIZATION
      -> TENANT
          -> RESOURCE
```

#### Platform administration

Manages platform-owned lifecycle and operations such as Tenant/network creation,
suspension/recovery, security administration and auditing.

Platform administrators do not automatically receive unrestricted access to
private Tenant business data.

A single logical `PLATFORM_ROOT` / break-glass identity may exist for bootstrap
and catastrophic recovery, but it is not a daily administrator account. Future
controls should include strong authentication, short sessions, explicit reason,
alerts and tamper-resistant auditing.

#### Network / organization administration

Represents a business operator responsible for multiple Tenants, for example a
retail network containing multiple stores.

A network-scoped principal may move between authorized Tenants and administer
resources within that network but cannot manage unrelated networks or platform
ownership/lifecycle.

The model must remain compatible with future relationship-based authorization if
Platform -> Network -> Tenant -> Resource inheritance makes flat RBAC
insufficient.

#### Tenant workspace

Tenant roles established through OH-013/OH-014 remain Tenant-scoped. Network or
Platform authority never silently converts into unrestricted Tenant-private-data
access.

## S4 — Business Administration

### OH-018 — Catalog and Inventory administration

Status: PLANNED — depends on OH-013 and the relevant Tenant administration
surface.

Expose controlled administration capabilities such as:

- create/update Catalog products and variants;
- Category management;
- pricing/base-price management;
- Inventory receipt;
- auditable Inventory adjustment;
- safety-stock management;
- Inventory oversell-policy management (`DENY` / `ALLOW_BACKORDER`);
- later warehouse/location administration when that domain exists.

Representative permissions include system-owned capabilities such as
`CATALOG_*`, `INVENTORY_RECEIVE`, `INVENTORY_ADJUST` and
`INVENTORY_POLICY_MANAGE`; exact codes are defined/versioned by the authorization
catalog rather than invented by Tenant custom roles.

Inventory changes are represented as auditable movements/adjustments rather than
untraceable `set quantity` operations.

Administrative write operations require explicit permissions and produce an
audit trail.

The Inventory Policy administration requirement identified after OH-012 is
preserved here explicitly; it is a consumer of the authorization foundation,
not a substitute for it.

## Identity provisioning and account lifecycle — planned

Authenticated internal identity already supports durable external identity
bindings, but operational provisioning remains incomplete.

Future work must cover concrete workflows such as:

- Staff invitation/provisioning into an authorized Tenant;
- Customer account linking when a guest/commercial identity becomes an
  authenticated User;
- external identity link/unlink/relink lifecycle;
- recovery and provider migration without changing internal User identity;
- membership suspension/termination semantics;
- privacy-safe anti-enumeration behavior;
- auditable privileged provisioning changes.

This work should be attached to the first administration/self-service workflow
that actually needs it rather than inventing an identity-management product in
isolation.

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

## Privileged access and audit — planned and incrementally enforced

High-impact capabilities must be separated from ordinary administration.

Direction:

- immutable/append-oriented audit evidence for privilege changes and privileged
  operations;
- actor, persona, effective scope, action, resource, outcome, reason and
  correlation metadata without copying unnecessary private payloads;
- support/recovery operations should be callable without granting broad read
  access to Tenant data;
- protected/system roles require stronger mutation rules than ordinary roles;
- later evolution may introduce just-in-time privileged activation, expiry,
  approval and periodic access review when operational complexity warrants it.

OH-013 establishes authorization primitives; later slices expand the privileged
operational workflows without bypassing those primitives.

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
- richer customer loyalty/benefit models after CustomerProfile exists;
- search indexing when PostgreSQL Catalog discovery stops meeting measured needs;
- horizontal scaling/capacity tuning based on benchmarks rather than assumptions;
- mature custom-role lifecycle, JIT privileged access and access reviews;
- organization/store-group relationship models when multi-Tenant administration
  has a concrete business requirement;
- dedicated analytical storage/processing only when transactional-query workload,
  retention or analytical consumers justify it.

None of these items is automatically in scope merely because it appears in this
roadmap. Each requires a concrete use case, threat/failure model, acceptance
criteria and evidence before implementation.

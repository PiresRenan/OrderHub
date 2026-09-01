# ADR-0009 — Tenant Catalog, Inventory and Atomic Order Commitment

Status: TESTED

## Context

OH-010 established a stateless authenticated request boundary and trusted tenant
context. OrderHub can now prove which internal User is making a request and that
the User belongs to the selected Tenant before Orders receives tenant context.

S1 still lacks a business-critical invariant: accepting an Order must not create
an obligation that violates the Tenant's inventory policy.

The original Orders model carries a generic `productId` and quantity on each
Order line. That was sufficient before Product Catalog existed, but it is not a
sufficient long-term commercial model.

A flat Product containing one stock quantity and one floating-point price would
create several problems:

- abstract Products and concrete sellable SKUs would be conflated;
- variants such as size/color could not own independent stock or identifiers;
- category hierarchy would be encoded as fixed group/subgroup columns;
- future B2B price scopes would force changes inside an oversized Product
  aggregate;
- images/media and external product identifiers would have no clear owner;
- Inventory correctness would be attached to the wrong business identity.

OH-011 therefore establishes first-class `catalog` and `inventory` modules before
integrating inventory commitment into Order creation.

The implementation is intentionally smaller than a complete PIM, pricing engine,
warehouse-management system or storefront, but its boundaries must remain
compatible with those future capabilities.

## Research evidence — 2026-08-31

The design was checked against current official commerce/platform documentation.

### Product and Variant separation

commercetools documents Product as the parent/abstract merchandising structure
and ProductVariant as the concrete sellable good/SKU. Variant data includes SKU,
prices, images/assets and inventory-related information. Inventory is modeled at
ProductVariant level.

References:

- https://docs.commercetools.com/learning-model-your-product-catalog/product-modeling/products
- https://docs.commercetools.com/learning-model-your-product-catalog/product-modeling/modeling-products
- https://docs.commercetools.com/api/product-catalog-overview

Shopify likewise uses ProductVariant as the link between SKU, price, inventory,
media and selling behavior.

Reference:

- https://shopify.dev/docs/api/admin-graphql/unstable/enums/ProductVariantInventoryPolicy

### Categories

commercetools models Categories as hierarchical parent/child trees. Products may
belong to multiple Categories, which avoids limiting catalog navigation to a
fixed number of levels.

References:

- https://docs.commercetools.com/learning-model-your-product-catalog/categorization/structure-your-product-categories
- https://docs.commercetools.com/api/projects/categories

### Pricing

Current commercetools pricing supports scopes such as currency, country,
customer group, channel and validity context. OH-011 does not implement that
pricing engine, but the Catalog model must not force future B2B pricing into a
single mutable `product.price` field.

Reference:

- https://docs.commercetools.com/learning-price-and-discount-your-products/price-calculation/price-selection

### Standard product identifiers

Google Merchant Center distinguishes identifiers such as GTIN, brand and MPN and
also explicitly supports products that legitimately do not possess standardized
identifiers. Those identifiers therefore remain optional in OrderHub rather than
being fabricated to satisfy a schema.

Reference:

- https://support.google.com/merchants/answer/7162856

### PostgreSQL concurrency

PostgreSQL `READ COMMITTED` re-evaluates an `UPDATE` search condition against the
new row version after waiting for a concurrent updater. Row locks are held until
transaction end. PostgreSQL recommends consistent lock acquisition order as a
primary deadlock-avoidance technique.

References:

- https://www.postgresql.org/docs/18/transaction-iso.html
- https://www.postgresql.org/docs/17/explicit-locking.html

### Programmatic transactions

Spring supports imperative programmatic transaction demarcation through
`TransactionTemplate` / `TransactionOperations`, allowing infrastructure to own
framework transaction APIs while application/domain code remains framework
neutral.

Reference:

- https://docs.spring.io/spring-framework/reference/data-access/transaction/programmatic.html

## PR #23 correctness review — 2026-09-01

Automated review of PR #23 against commit
`870ad9b822da9a522c640a260e767e824ec5fa51` identified two correctness
gaps that invalidate final OH-011 acceptance until corrected:

- Order placement currently commits Inventory without proving that the
  referenced Catalog ProductVariant is still eligible for new business;
- Category hierarchy validation and persistence are currently separate
  unlocked operations, allowing conflicting concurrent reparenting to
  validate against stale hierarchy state.

A review of the same Catalog lifecycle boundary also makes the parent
Product lifecycle relevant to Order acceptance: a Product in `DRAFT` or
`ARCHIVED` state is not commercially eligible merely because one child
ProductVariant remains `ACTIVE`.

These findings are release-blocking for OH-011. Required remote checks
passing on the reviewed commit prove the tested implementation behaved as
written; they do not override newly discovered missing invariants.

PostgreSQL documents that application-level consistency checks under
`READ COMMITTED` require explicit locking when the checked row must remain
valid against concurrent updates. `SELECT ... FOR SHARE` prevents
concurrent `UPDATE` and `DELETE` of the selected rows until transaction end.

References:

- https://www.postgresql.org/docs/18/applevel-consistency.html
- https://www.postgresql.org/docs/18/explicit-locking.html
- https://www.postgresql.org/docs/18/sql-select.html
- https://www.postgresql.org/docs/18/runtime-config-client.html
- https://docs.spring.io/spring-framework/reference/data-access/transaction/programmatic.html
- https://docs.spring.io/spring-modulith/docs/current/api/org/springframework/modulith/core/NamedInterface.html
- https://shopify.dev/docs/api/admin-graphql/2026-01/enums/ProductStatus
- https://docs.commercetools.com/api/projects/carts
- https://www.rfc-editor.org/rfc/rfc9110.html#section-15.5.10

## Decision

Introduce two separate Spring Modulith root modules:

```text
catalog
inventory
```

They remain separate because Product information/merchandising and physical
inventory commitments have different ownership and lifecycle even when the same
business workflow uses both.

Orders may consume only explicit public application/module contracts.

No module may import another module's JDBC adapter, table mapper or persistence
implementation.

## Catalog ownership

### Product

`Product` represents a tenant-owned merchandising concept, not the concrete
stock-tracked item.

The initial Product model is expected to contain at least:

- `id` — opaque UUID;
- `tenantId` — owner scope;
- `name` — required bounded commercial/display name;
- `slug` — tenant-scoped human-readable identifier suitable for future URLs;
- `status` — lifecycle state rather than physical deletion as the normal way to
  retire referenced catalog data;
- optional `description`;
- optional `brand`;
- category assignments;
- created/updated metadata at persistence/application boundaries where required.

A Product may exist as `DRAFT` without any Variant while catalog authoring is
incomplete. Commercial usability is stricter: activation requires at least one
sellable Variant belonging to the same Tenant and Product.
Product lifecycle must preserve historical referential meaning. A Product or
Variant referenced by an existing Order must not silently become a different
business item through identifier reuse.

### Product commercial metadata invariants

`brand` remains optional Product metadata.

When a brand is present:

- it must contain non-whitespace content;
- surrounding Unicode whitespace is removed at the domain boundary;
- the normalized value must contain at most 120 Unicode code points;
- ISO control characters are forbidden;
- internal spacing, capitalization, punctuation and Unicode representation are otherwise preserved.

ProductVariant may additionally provide an optional `displayName` when the
sellable SKU needs presentation text distinct from its parent Product.

When `displayName` is present:

- it must contain non-whitespace content;
- surrounding Unicode whitespace is removed at the domain boundary;
- the normalized value must contain at most 160 Unicode code points;
- ISO control characters are forbidden;
- its remaining representation is preserved exactly.

### ProductVariant

`ProductVariant` is the concrete sellable unit.

It is expected to contain at least:

- `id` — opaque UUID;
- `tenantId` — explicit owner scope;
- `productId` — owning Product;
- `sku` — required merchant/business identifier, unique within the Tenant;
- optional display/name override when the sellable variant needs one;
- optional standardized identifiers such as GTIN;
- optional manufacturer reference such as MPN;
- optional variant attributes required to distinguish sellable choices such as
  size/color/material;
- lifecycle state allowing a variant to stop accepting new business without
  destroying historical references.

The initial ProductVariant lifecycle is:

- `DRAFT` - catalog authoring state; not sellable;
- `ACTIVE` - sellable and eligible for new business;
- `INACTIVE` - temporarily unavailable for new business while identity and history remain intact;
- `ARCHIVED` - retired from normal commercial use while historical identity remains preserved.

Only an `ACTIVE` ProductVariant is considered sellable for Product activation
and new Order placement. `INACTIVE` is deliberately distinct from `ARCHIVED`
so temporary commercial suspension does not imply permanent retirement.

### Order-placement sellability invariant

A new Order may reference a Variant only while all of the following are true
for the same Tenant:

- the ProductVariant exists;
- the ProductVariant is `ACTIVE`;
- its owning Product exists;
- its owning Product is `ACTIVE`;
- the Variant still belongs to that Product.

Missing, cross-Tenant or non-active Catalog identity fails closed using one
stable non-enumerating application result.

Order creation consumes this rule only through a public framework-neutral
Catalog application contract. Orders must not import Catalog domain models,
persistence ports, JDBC adapters or tables.

The Catalog eligibility check executes inside the Order-owned physical
transaction before any Inventory position mutation. The PostgreSQL Catalog
adapter acquires `FOR SHARE` row locks for the relevant Product and
ProductVariant records so a concurrent lifecycle update cannot invalidate
the accepted eligibility observation before that Order transaction ends.

### Deterministic Catalog locking protocol

The Order-placement check deliberately avoids relying on one broad joined
read as its sole correctness boundary under `READ COMMITTED`.

The Catalog application service follows this exact lock protocol:

1. reject null/invalid input before persistence access;
2. deduplicate requested Variant UUIDs;
3. sort Variant UUIDs using one deterministic JVM-wide ordering rule;
4. for each Variant, execute a simple tenant-scoped PostgreSQL query equivalent
   to `SELECT product_id ... WHERE tenant_id = ? AND id = ? AND status =
   'ACTIVE' FOR SHARE`;
5. treat zero rows as one generic Catalog orderability rejection;
6. obtain the Product identity only from the successfully locked Variant row;
7. deduplicate and deterministically sort those Product UUIDs;
8. for each Product, execute a simple tenant-scoped PostgreSQL query equivalent
   to `SELECT id ... WHERE tenant_id = ? AND id = ? AND status = 'ACTIVE'
   FOR SHARE`;
9. treat zero rows as the same generic Catalog orderability rejection;
10. only after every Catalog identity is locked and eligible may Inventory
    mutation begin.

The locked Variant row stabilizes its status and Product relationship for the
remainder of the Order transaction. The subsequently locked Product row
stabilizes Product commercial eligibility for the same interval.

Under PostgreSQL `READ COMMITTED`, a locking statement that encounters a
concurrently updated target waits for that transaction and then applies its
predicate to the updated row version. Consequently an Order must not accept
an obsolete ACTIVE observation after a concurrent lifecycle change commits.

Catalog/Inventory workflows use one global acquisition order:

```text
Catalog Variant rows — deterministic UUID order
Catalog Product rows — deterministic UUID order
Inventory Position rows — deterministic UUID order
```

Any future transaction that needs more than one of those resource classes must
either preserve that order or introduce a new ADR proving an alternative.

The locks are PostgreSQL transaction-scoped locks and therefore coordinate
independent OrderHub replicas. JVM monitors, process-local locks and
best-effort cache locks remain invalid correctness mechanisms.

The existing bounded Order transaction timeout also bounds these Catalog lock
waits. The current 5-second value remains a provisional safety baseline rather
than an SLA, capacity claim or automatic-retry trigger.

Orders consumes Catalog only through a public framework-neutral named interface.
The initial contract is a narrow orderability use case; Catalog domain models,
persistence ports, JDBC adapters and SQL remain internal.

Orders is therefore allowed to consume only the explicitly exposed
`catalog::api` and `inventory::api` named interfaces for this workflow.

### ProductVariant identifier invariants

GTIN and MPN remain optional commercial identifiers. Their absence is valid
and OrderHub must never fabricate either value merely to satisfy persistence.

When a GTIN is present:

- it is stored as an opaque digit string rather than a numeric type;
- its supplied representation is preserved, including meaningful leading zeroes;
- accepted lengths are GTIN-8, GTIN-12, GTIN-13 and GTIN-14;
- only decimal digits are accepted;
- the GS1 check digit must be valid;
- OrderHub does not silently pad a shorter GTIN to fourteen digits.

When an MPN is present:

- it represents a manufacturer-assigned part number rather than an OrderHub identifier;
- it must contain between 1 and 70 Unicode code points;
- surrounding Unicode whitespace is forbidden;
- ISO control characters are forbidden;
- otherwise the supplied representation is preserved exactly;
- case conversion or punctuation normalization is forbidden;
- an unknown MPN remains absent rather than being guessed or synthesized.

The Product/Variant relationship is not replaced with duplicated Products for
every SKU merely to simplify persistence.

Inventory is keyed by ProductVariant identity.

### Order contract terminology

Once Catalog exists, an Order line must identify the concrete sellable unit.
The current `productId` Order-line name would become semantically false if it
actually stored a ProductVariant UUID.

Because OrderHub is still pre-1.0, OH-011 will deliberately evolve the Orders
contract from `productId` to `variantId` rather than preserve misleading naming
for compatibility.

The migration must be explicit across HTTP DTOs, application commands, domain
models, SQL schema evolution and tests. Existing accepted migration files remain
immutable; database changes use forward migrations.

## Category model

Fixed `group` / `subgroup` fields are rejected.

`Category` is a tenant-scoped tree node with at least:

- `id`;
- `tenantId`;
- `name`;
- `slug`;
- optional `description`;
- optional `parentCategoryId`;
- deterministic sibling/display ordering metadata when required.

A Category may have zero or many child Categories and at most one direct parent.
A Product may belong to multiple Categories.

Parent relationships and Product assignments must never cross Tenant scope.
Cycles are invalid and must be rejected by the application boundary even though
a simple relational foreign key alone cannot prevent every arbitrary-depth
cycle.

### Category hierarchy concurrency

Hierarchy validation and hierarchy persistence form one atomic consistency
operation. A read-validate-write sequence running without coordination is
insufficient under concurrent reparenting.

All Category hierarchy mutations belonging to the same Tenant are serialized
through one PostgreSQL transaction-scoped Catalog guard before ancestry
validation begins. The guard remains held through Category persistence and is
released by transaction completion.

V10 introduces a Catalog-owned guard table conceptually equivalent to:

```text
catalog.category_hierarchy_guards
  tenant_id UUID PRIMARY KEY
```

The guard intentionally contains no cross-module foreign key. Its identity is
the exact Tenant UUID supplied through the already trusted module boundary.

Guard acquisition is deterministic and transaction-bound:

1. start the Category hierarchy transaction;
2. provision the Tenant guard row with `INSERT ... ON CONFLICT DO NOTHING`;
3. acquire that exact row using `SELECT ... WHERE tenant_id = ? FOR UPDATE`;
4. only after acquiring the guard, traverse and validate the current ancestry;
5. persist the Category mutation;
6. commit or roll back, releasing the guard.

Concurrent first use is part of the correctness model: competing inserts for
the same Tenant must converge on the same durable guard row before hierarchy
validation proceeds.

The guard is not a JVM monitor, distributed cache lock, hash-derived advisory
lock or global table lock. Different Tenants therefore use different rows and
remain independently mutable.

The application service retains ownership of ancestry validation. A
framework-neutral Category hierarchy mutation executor owns only the atomic
`guard + validate + save` execution contract, while its PostgreSQL/Spring
adapter owns transaction demarcation and guard acquisition.

Category hierarchy transaction waiting is finite and externally configurable.
The initial configuration baseline is
`orderhub.catalog.category-hierarchy.transaction.timeout=5s`.
As with the Order transaction timeout, this value is a provisional safety
bound and not a performance SLA.

A deliberate held-guard acceptance test must prove bounded termination rather
than assuming transaction-manager timeout propagation.

For concurrent root mutations A->B and B->A within the same Tenant, one
transaction obtains the guard first. The second validates only after the first
commits and must then observe and reject the would-be cycle. Persisting A<->B
is never an acceptable outcome.

Catalog-owned composite references may use database foreign keys because all
participating tables belong to the same module.

## Product attributes

OH-011 must support the idea that different variants may carry differentiating
attributes, but it will not build a complete dynamic Product-Type/PIM schema
engine.

The initial attribute representation is a collection of explicit key/value
facts owned by one ProductVariant.

Attribute keys are machine-readable identifiers and must:

- contain between 1 and 64 characters;
- start with an ASCII letter;
- contain only ASCII letters, digits, period, underscore or hyphen after the first character;
- be unique within one Variant using exact case-sensitive comparison;
- be preserved exactly rather than silently lowercased or otherwise canonicalized.

Attribute values must:

- exist and contain non-whitespace content;
- contain at most 256 Unicode code points;
- contain no ISO control characters;
- contain no surrounding Unicode whitespace;
- otherwise be preserved exactly.

Attributes remain tenant/product scoped transitively through their owning Variant.
They are persisted relationally rather than using an arbitrary JSON blob as the
primary correctness boundary.

SKU, GTIN, MPN, price and Inventory remain first-class concepts and must not be
hidden inside the attribute collection.

If later catalog requirements need typed attribute definitions, localization,
faceting or validation schemas, that becomes a separate evidence-driven Product
Type/PIM evolution.

## Money and pricing

Binary floating-point (`float` / `double`) is forbidden for monetary values.

The Catalog owns a dedicated monetary/price concept rather than placing a mutable
primitive `price` directly on Product.

The initial base-price capability must include at least:

- tenant scope;
- Variant identity;
- ISO currency code;
- exact amount represented as non-negative integer minor units;
- non-negative invariant;
- optional validity metadata only if a concrete OH-011 use case/tests require it.

OH-011 standardizes the initial `Money` representation on an ISO 4217
currency code plus non-negative integer minor units.

Conceptually:

```text
Money
  currencyCode
  minorUnits
```

The persistence model stores the currency code and integer minor-unit amount
directly, without binary floating-point arithmetic or implicit rounding.

### Money and base-price invariants

The initial Money value is structurally represented by:

```text
currencyCode
minorUnits
```

`currencyCode` must:

- be a three-letter ISO 4217 currency code recognized by the Java runtime;
- use its canonical uppercase representation;
- never be guessed from Tenant, locale, country or request context.

`minorUnits` must:

- use signed 64-bit integer storage;
- be greater than or equal to zero;
- represent the exact amount in the currency minor unit;
- never pass through binary floating-point arithmetic.

Zero-valued Money is valid. Whether a zero price is commercially appropriate is
a pricing/business-policy concern rather than a structural Money invariant.

OH-011 introduces `VariantBasePrice` as a separate Catalog-owned concept.

A VariantBasePrice contains:

- `tenantId`;
- `variantId`;
- `currencyCode`;
- `minorUnits`.

There is at most one base price for the same
`(tenantId, variantId, currencyCode)` tuple.

The Variant reference is tenant-safe and may use a same-module composite foreign
key to `catalog.product_variants (tenant_id, id)`.

A Variant may have zero or multiple base prices when those prices use different
currencies. Base price presence does not itself activate a Product or Variant.

OH-011 does not perform runtime price selection. Customer groups, channels,
validity windows, negotiated prices, promotions, discounts and tax remain
separate future pricing concerns.

The schema/model must leave a clear migration path toward future B2B price lists
or scopes such as customer group/channel/validity, but OH-011 does not implement
price selection, discount, tax, promotion or negotiated-contract pricing.

## Product media

Catalog metadata may associate ordered media references with Product and/or
ProductVariant.

The initial representation may contain:

- media identifier;
- owner Product/Variant;
- media type;
- reference/URL or future storage key;
- optional alt text;
- sort order / primary presentation marker.

### Catalog media invariants

The initial media record contains:

- `mediaId`;
- `tenantId`;
- exactly one owner: `productId` XOR `variantId`;
- `mediaType`;
- `reference`;
- optional `altText`;
- `sortOrder`;
- `primary`.

Media ownership is constrained as follows:

- exactly one of `productId` or `variantId` must be present;
- both owner columns belonging to the same record is invalid;
- both owner columns being absent is invalid;
- Product ownership uses a tenant-safe same-module foreign key;
- Variant ownership uses a tenant-safe same-module foreign key;
- cross-Tenant ownership is impossible at the relational boundary.

`mediaType` initially supports:

- `IMAGE`;
- `VIDEO`;
- `DOCUMENT`;
- `OTHER`.

`reference` is metadata, not an instruction to perform a network request.

A media reference must:

- contain non-whitespace content;
- contain at most 2048 Unicode code points;
- contain no surrounding Unicode whitespace;
- contain no ISO control characters;
- otherwise be preserved exactly.

OrderHub does not require the reference to be an HTTP URL. It may represent a
future object-storage key, CDN identifier, URI or other externally interpreted
media reference.

When `altText` is present:

- it must contain non-whitespace content;
- it must contain at most 512 Unicode code points;
- surrounding Unicode whitespace is removed;
- ISO control characters are forbidden.

`sortOrder` is a non-negative integer.

At most one media record may be `primary = true` for a Product and at most one
may be primary for a ProductVariant. PostgreSQL partial unique indexes provide
the relational enforcement for those two owner forms.

Multiple non-primary media records remain valid and are ordered deterministically
by `sortOrder`, with `mediaId` available as the stable tie-breaker.

OH-011 does not implement binary upload, object storage, image transformation or
server-side retrieval of arbitrary external URLs. Treating media as metadata
avoids introducing an SSRF-capable remote-fetch path merely to support catalog
references.

Images are therefore optional descriptive data, not a prerequisite for a valid
sellable Variant.

## Inventory position

Inventory state is tenant + Variant scoped.

Initial quantities:

- `onHand` — physical stock known to be present;
- `committed` — physical stock allocated to accepted Orders;
- `backordered` — accepted demand not yet backed by physical stock;
- `safetyStock` — physical stock deliberately withheld from sale.

Conceptually:

```text
availableToPromise = onHand - committed - safetyStock
```

Invariants:

```text
onHand >= 0
committed >= 0
backordered >= 0
safetyStock >= 0
committed <= onHand
```

`safetyStock` is a commercial availability threshold, not a second physical
allocation counter. It may therefore exceed the currently available physical
stock.

When available-to-promise is negative, allocatable demand is clamped at zero:

```text
allocatable = max(0, availableToPromise)
```

Physical stock does not become negative to represent backorder demand.

Durable quantity accumulators use a range appropriate to persistent aggregate
stock totals and do not assume the current `int` Order-line quantity is an
adequate database accumulator type.

## Inventory policy

Inventory policy belongs to Inventory and is configured per Tenant.

Initial policy:

```text
DENY
ALLOW_BACKORDER
```

### DENY

If requested quantity exceeds available-to-promise quantity, the complete Order
fails with no durable Inventory or Order effect.

### ALLOW_BACKORDER

Available physical stock is committed and only the uncovered remainder is added
to backordered demand.

Example:

```text
availableToPromise = 3
requested          = 5

allocated          = 3
backordered        = 2
```

A missing Tenant inventory policy or missing Variant inventory position fails
closed. Order placement never invents Product, Variant, Policy or Inventory
state implicitly.

## Temporary reservation

Temporary reservation is not the same concept as Order commitment.

Reservation requires a real lifecycle such as cart/checkout/payment hold,
expiration and release. No such lifecycle exists yet.

OH-011 therefore excludes temporary reservation and TTL. The concept remains in
the roadmap for the first business flow that actually requires it.

## Inventory commitment ledger

Counters alone are insufficient for later cancellation, release and
reconciliation.

Inventory therefore owns a durable `InventoryCommitment` concept containing at
least:

- commitment identity;
- tenantId;
- orderId as an external aggregate reference;
- variantId;
- requested quantity;
- physically allocated quantity;
- backordered quantity;
- creation metadata.

Invariant:

```text
requested = allocated + backordered
```

There is at most one logical commitment for the same
`(tenantId, orderId, variantId)`.

Duplicate Order lines for the same Variant are aggregated before Inventory
mutation so a single Order does not acquire the same position lock multiple
times or create ambiguous duplicate commitments.

Inventory does not create a database foreign key to Orders because that would
couple module-owned schemas. Same-module references between Inventory-owned
tables may use foreign keys.

## Transaction boundary evolution

ADR-0005 deliberately placed the transaction inside the PostgreSQL
OrderRepository because OH-007 persisted only one aggregate through one output
port. ADR-0005 also explicitly deferred a broader Unit-of-Work abstraction until
a use case needed to coordinate multiple independent transactional operations.

OH-011 creates that requirement.

`POST /orders` must make these effects one physical PostgreSQL transaction:

```text
Order persistence
Inventory position mutation
Inventory commitment persistence
```

Application/domain code remains free of Spring transaction annotations/types.

The preferred implementation direction is a narrow framework-neutral transaction
execution boundary implemented in infrastructure/composition with Spring
programmatic transaction support.

A generalized enterprise Unit-of-Work framework is rejected unless a later
requirement proves it necessary.

Existing repository-level `TransactionTemplate` behavior may participate in the
outer physical transaction through REQUIRED semantics, but tests must prove the
actual cross-module rollback behavior rather than relying on assumed propagation.

## Transaction operation order

Baseline operation order:

```text
BEGIN
  construct and persist Order
  collect distinct ordered Variant identities
  validate and lock Catalog Product/Variant sellability
  aggregate duplicate Variant lines
  sort Inventory mutations deterministically
  commit Inventory positions
  persist InventoryCommitments
COMMIT
```

Inventory is intentionally mutated near transaction end because hot Inventory
rows are expected to be more contention-sensitive than inserting a new Order.
Reducing lock holding time is preferred provided rollback tests prove the whole
unit remains atomic.

If Order persistence fails, Inventory is never mutated.
If Catalog rejects Product/Variant sellability after Order insertion, the Order
insertion rolls back and Inventory remains unchanged.
If Inventory mutation or commitment persistence fails after Order insertion, the
Order insertion rolls back.

## Concurrency strategy

Read-check-write application logic such as:

```text
SELECT quantity
if enough
UPDATE quantity
```

is rejected for the oversell invariant.

The baseline strategy is an atomic conditional PostgreSQL mutation whose
predicate contains the availability requirement.

Under PostgreSQL `READ COMMITTED`, when an updater waits for another transaction
to update the same row, PostgreSQL applies the search condition again to the
updated row version before deciding whether the waiting UPDATE still qualifies.

This permits a `DENY` operation to map affected-row count deterministically:

```text
1 row -> commitment mutation succeeded
0 rows -> current state no longer satisfies availability requirement
```

`SELECT ... FOR UPDATE` remains available when a future state transition requires
read-before-write semantics that cannot be expressed safely as one mutation; it
is not the default merely because row locking exists.

Process-local locks are forbidden as a correctness mechanism because OrderHub is
already designed to run with multiple replicas.

## Multi-item deadlock prevention

Atomic UPDATE still acquires row locks.

Two Orders containing the same Variants in opposite request order can otherwise
produce a lock-order cycle.

Before Inventory mutations, duplicate lines are aggregated and Variant IDs are
sorted using one deterministic ordering rule independent of incoming JSON order.

PostgreSQL deadlock detection remains a final safety mechanism; consistent lock
ordering is the preventive design.

## Isolation and timeout

The baseline transaction isolation remains PostgreSQL `READ COMMITTED`.

Global SERIALIZABLE isolation is rejected without evidence of an anomaly that the
atomic mutations, constraints and deterministic row locking cannot prevent.

Lock/transaction waiting must be finite and externally configurable. Initial
values are safety baselines and must not be described as proven latency/capacity
numbers before benchmark evidence.

For OH-011, `orderhub.orders.transaction.timeout=5s` is the initial production
safety baseline. The value is deliberately provisional: it bounds
lock/transaction waiting before load and latency evidence exists, but it is not
an SLA, capacity claim or retry trigger. Tests may override the value with a
shorter duration to prove bounded failure deterministically.

Broad automatic retry of deadlock/lock-timeout failures is deferred until the
Order HTTP boundary has durable business idempotency.

## Order result semantics

Order lifecycle and Inventory allocation are different state machines.

`OrderStatus` will not gain `BACKORDERED` merely to communicate Inventory state.

The create-order application result must evolve so callers can distinguish at
least:

```text
FULLY_ALLOCATED
PARTIALLY_BACKORDERED
FULLY_BACKORDERED
```

without treating an accepted backordered Order as an asynchronous creation.

An accepted Order still returns `201 Created`.

Insufficient Inventory under `DENY` is represented as a stable privacy-safe
`409 Conflict` response because the command is structurally valid but conflicts
with current business state.

Catalog orderability rejection uses the same HTTP status class but a distinct
stable business problem type. Missing Variant, cross-Tenant Variant,
non-`ACTIVE` Variant, missing owning Product and non-`ACTIVE` owning Product
must intentionally collapse into one non-enumerating `409 Conflict` contract.

The HTTP body must not disclose whether a supplied Variant exists, which Tenant
owns it, its lifecycle state, its Product identity or the Product lifecycle
state. SQL/JDBC details remain forbidden.

## Database ownership and migrations

Flyway V1-V9 are immutable for the remainder of OH-011.

The PR-review correction continues through forward migration V10.
V10 introduces the Catalog-owned tenant-scoped Category hierarchy mutation
guard required to serialize ancestry validation and persistence.
No cross-module foreign key is introduced by that guard.

Dedicated schemas:

```text
catalog
inventory
```

Catalog owns all Product, Variant, Category, Price, Media and same-module
association tables introduced by this issue.

Inventory owns Position, Policy and Commitment tables.

Cross-module database foreign keys remain prohibited.

Database constraints provide defense-in-depth for impossible quantity, monetary,
identity and same-module referential states.

## Security and authorization boundary

OH-011 does not expose public Catalog/Inventory administration HTTP endpoints.

Tenant membership alone is sufficient to enter a Tenant but is not sufficient
authorization to adjust stock, alter price, modify Product data or change
Inventory policy.

Those operations create the concrete requirement for the planned fine-grained
authorization phase recorded in `docs/ROADMAP.md`.

Until that authorization exists, Catalog and Inventory management capabilities
remain internal application/module boundaries and test fixtures rather than an
unsafe authenticated-by-membership administration API.

## Observability

OH-011 adds only low-cardinality operational dimensions such as allocation
outcome/failure category and transaction/lock timing.

Catalog orderability rejection is a business-state failure and must never be
misclassified as `technical_failure`. The create-Order failure metric therefore
adds one bounded generic reason:

```text
catalog_item_unavailable
```

The value intentionally does not distinguish missing identity, Tenant mismatch,
Variant lifecycle or Product lifecycle.

Tenant IDs, Product IDs, Variant IDs, SKUs and external identifiers must not
become unbounded metric labels.

Correlation/request IDs remain the diagnostic mechanism for individual request
investigation.

Logs and errors must not expose cross-tenant data, SQL/JDBC details, full catalog
payloads or unnecessary commercial identifiers.

## OH-011 pre-PR specialist assurance gate

The discovery of correctness gaps during PR review invalidated the previous
assumption that passing the existing functional and CI suites was sufficient
evidence for OH-011.

Before the corrected implementation may be pushed for final review, the complete
diff must pass all of the following independent review lenses:

1. **Domain / commerce** — lifecycle state machines, parent-child eligibility,
   missing-state behavior and historical identity preservation.
2. **PostgreSQL / concurrency** — TOCTOU, lost update, write skew, lock ordering,
   deadlocks, finite waits, first-use races and independent connections.
3. **Transaction / reliability** — every durable failure point proves rollback
   and zero unintended business effects.
4. **Architecture / Modulith** — module discovery, acyclic dependencies, named
   interfaces and absence of imports into another module's internals.
5. **Security / multi-tenancy / privacy** — Tenant isolation, non-enumeration,
   safe logs, safe HTTP errors and absence of business IDs in metric labels.
6. **API semantics** — stable status codes, Problem Details and business versus
   technical failure classification.
7. **Observability / SRE** — bounded metric cardinality, correct failure taxonomy,
   actionable transaction/lock evidence and no identifier leakage.
8. **Schema / DBA** — forward-only migration, V1-V9 immutability, constraints,
   clean V1-V10 bootstrap and concurrency behavior on real PostgreSQL.
9. **Adversarial QA** — negative paths and deterministic concurrency barriers;
   timing luck is not accepted as race-condition evidence.
10. **Runtime / multi-replica** — correctness is independent of JVM-local state
    and remains valid across at least two independent application instances
    where the invariant crosses process boundaries.
11. **Regression** — complete `clean verify`, repository whitespace and platform
    runtime validation remain green.
12. **Independent final review** — the final diff is reviewed again against this
    ADR and its executable acceptance matrix before ADR promotion or merge.

A green result in one lens cannot waive a failure in another.

Passing CI demonstrates only the properties covered by CI. It is not evidence
that an uncovered business or concurrency invariant is correct.
## Rejected alternatives

### One flat Product table with quantity and price

Rejected because Product and sellable Variant have distinct commercial semantics,
and Inventory belongs to the concrete sellable unit.

### Fixed group/subgroup columns

Rejected because real catalog navigation requires arbitrary hierarchy and
multi-category membership.

### `double` price

Rejected because binary floating-point is not an acceptable money representation.

### Full PIM/pricing engine in OH-011

Rejected because Product Types, localization, customer-group price selection,
discounts, promotion rules, tax and advanced publication workflows have no
current S1 use case requiring their implementation.

### Negative on-hand stock for backorders

Rejected because physical stock and uncovered demand are different facts.

### Process-local inventory lock

Rejected because correctness would fail when two OrderHub replicas process the
same Variant concurrently.

### Global SERIALIZABLE isolation

Rejected until a tested anomaly proves the narrower atomic SQL/locking strategy
insufficient.

### Inventory reservation TTL now

Rejected because no cart/checkout/payment-hold lifecycle exists yet.

## Verification plan

ADR-0009 is `TESTED`. The checklist below records the executable evidence
satisfied before promotion:

### Catalog

- [x] Catalog is recognized as an independent Spring Modulith module.
- [x] Product and ProductVariant invariants are tested without Spring.
- [x] Category hierarchy and multi-category assignment are tenant-safe.
- [x] concurrent inverse Category reparenting cannot persist a cycle.
- [x] same-Tenant Category hierarchy mutations serialize across independent
      database connections while different Tenants retain independent guards.
- [x] concurrent first-use guard creation converges on one Tenant guard row.
- [x] Category ancestry validation begins only after its Tenant guard is held.
- [x] deliberate guard contention terminates within the configured finite
      transaction timeout.
- [x] a Category guard for Tenant A does not unnecessarily block Tenant B.
- [x] same-tenant SKU uniqueness is deterministic.
- [x] standardized identifiers are optional and cannot be fabricated by default.
- [x] money uses ISO 4217 currency plus exact integer minor-unit storage.
- [x] media metadata cannot create an implicit arbitrary-URL server-fetch path.
- [x] Order-line terminology is migrated from Product identity to Variant identity.
- [x] Order placement rejects missing or non-active ProductVariant identity.
- [x] Order placement rejects a Variant whose owning Product is not `ACTIVE`.
- [x] Catalog sellability is checked through an explicit public `catalog::api`
      named interface and Orders imports no Catalog internals.
- [x] requested Variants are deduplicated and locked in deterministic UUID order.
- [x] each Variant eligibility lookup is tenant-scoped, requires `ACTIVE`, uses
      `FOR SHARE` and obtains Product identity from the locked row.
- [x] owning Products are deduplicated and locked in deterministic UUID order.
- [x] each Product eligibility lookup is tenant-scoped, requires `ACTIVE` and
      uses `FOR SHARE`.
- [x] Catalog Product/Variant eligibility remains stable against concurrent
      lifecycle mutation until the Order transaction completes.
- [x] a lifecycle mutation committed before Catalog obtains its lock causes the
      waiting Order to observe current state and reject.
- [x] a lifecycle mutation started after Catalog has obtained its lock waits until
      the accepted Order transaction completes.
- [x] inverse multi-Variant request ordering cannot create a Catalog lock-order
      cycle.
- [x] Catalog rejection leaves zero durable Order or Inventory effects.
- [x] Catalog rejection maps to one privacy-safe `409 Conflict` family without
      identity or lifecycle enumeration.
- [x] Catalog rejection increments only bounded
      `failure{reason=catalog_item_unavailable}` observability.

### Inventory

- [x] Inventory is recognized as an independent Spring Modulith module.
- [x] Position, Policy and Commitment invariants are tested without Spring.
- [x] Inventory references sellable Variant identity.
- [x] missing Position/Policy fails closed.
- [x] `DENY` cannot oversell under real PostgreSQL concurrency.
- [x] `ALLOW_BACKORDER` produces correct allocated/backordered totals under
      concurrent demand.
- [x] duplicate Variant lines aggregate deterministically.
- [x] tenant A Inventory can never mutate tenant B Inventory.

### Transaction and failure behavior

- [x] Order + Inventory share one proven physical transaction.
- [x] Order persistence failure leaves Inventory unchanged.
- [x] Inventory failure after Order insert rolls Order back.
- [x] multi-item partial failure produces zero durable business effects.
- [x] deterministic Variant ordering is covered by inverse-order concurrency
      tests.
- [x] finite lock waiting is covered by a deliberate blocking test.
- [x] correctness is proven across more than one application replica/process
      boundary where the platform test is meaningful.

### Persistence and architecture

- [x] V1-V9 remain byte-for-byte unchanged by the review correction.
- [x] V10 creates the tenant-scoped Catalog Category hierarchy mutation guard.
- [x] V1-V10 migrations build Catalog/Inventory from an empty real PostgreSQL
      database.
- [x] PostgreSQL constraints reject impossible state when application validation
      is bypassed.
- [x] no cross-module persistence implementation imports/FKs are introduced.
- [x] Catalog and Inventory are explicitly detected as independent application
      modules.
- [x] Catalog exposes only the intended orderability contract through
      `catalog::api` for the Orders dependency introduced here.
- [x] Inventory remains independent of Catalog.
- [x] Spring Modulith verification remains green and no module cycle exists.

### Regression / repository gates

- [x] existing Orders, Tenants, Users and Security behavior remains green.
- [x] all twelve OH-011 pre-PR specialist review lenses are explicitly green.
- [x] `git diff --check` passes.
- [x] `.\mvnw.cmd clean verify` passes.
- [x] local reproduction of required repository workflows passes.
- [x] independent review of the final corrected diff reports no unresolved
      correctness finding.
- [x] `branch-policy`, `ci-build` and `platform-validation` pass on the final
      remote PR HEAD.

The complete verification gate was satisfied before this ADR was promoted to
`TESTED`.

## Verification evidence — 2026-09-01

Promotion to `TESTED` is backed by the complete OH-011 implementation,
specialist assurance gate, review-repair cycle and final independent review.

Implementation and review-repair commits:

- `ebbc35628951e029f191ba86eec678f6ebbf60bf` closes the Catalog
  lifecycle/orderability and concurrent Category hierarchy correctness gaps;
- `cba674e4c89aa0e600c2bd524e7415bdea0ed7a7` closes the follow-up
  technical-failure contract without leaking Catalog persistence internals
  across the Spring Modulith boundary.

Local evidence on the exact `cba674e4c89aa0e600c2bd524e7415bdea0ed7a7`
tree (`48537c606320197e235ac26efbd7cb6ff1da65d6`):

- `.\mvnw.cmd clean verify` completed successfully;
- 658 tests executed;
- 0 failures;
- 0 errors;
- 0 skipped;
- `git diff --check` passed;
- tested INDEX tree and committed tree were identical;
- Spring Modulith verification remained green.

Critical executable evidence includes:

- Catalog sellability acceptance: 8/8 GREEN;
- Catalog lifecycle concurrency acceptance: 4/4 GREEN;
- Category hierarchy concurrency acceptance: 4/4 GREEN;
- Inventory transaction integration: 7/7 GREEN;
- Inventory concurrency acceptance: 5/5 GREEN;
- multi-replica Inventory correctness: GREEN;
- Catalog persistence-to-public-API technical translation: GREEN;
- Catalog business HTTP privacy contract: GREEN;
- Catalog technical `500 / INTERNAL_ERROR` privacy contract: GREEN;
- Inventory HTTP regression: GREEN;
- Spring Modulith module boundaries: GREEN.

Required GitHub checks on
`cba674e4c89aa0e600c2bd524e7415bdea0ed7a7` completed successfully:

- `branch-policy` — SUCCESS;
- `ci-build` — SUCCESS;
- `platform-validation` — SUCCESS;

The final Codex re-review evaluated commit `cba674e4c8` after all three
review findings had been repaired and reported no major issues.

After that final re-review, all three PR #23 review threads were formally
resolved. No correctness blocker remained open when this ADR was promoted.
## Follow-up

The approved evolution roadmap is recorded in `docs/ROADMAP.md`.

In particular, Catalog/Inventory administration remains behind a later
fine-grained authorization boundary; durable request idempotency precedes broad
automatic retry/recovery; transactional outbox precedes reliable outbound
webhooks; and advanced PIM/pricing/warehouse capabilities require concrete new
business requirements before implementation.

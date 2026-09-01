# ADR-0009 — Tenant Catalog, Inventory and Atomic Order Commitment

Status: DESIGNED

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
  aggregate duplicate Variant lines
  sort Variant mutations deterministically
  commit Inventory positions
  persist InventoryCommitments
COMMIT
```

Inventory is intentionally mutated near transaction end because hot Inventory
rows are expected to be more contention-sensitive than inserting a new Order.
Reducing lock holding time is preferred provided rollback tests prove the whole
unit remains atomic.

If Order persistence fails, Inventory is never mutated.
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

## Database ownership and migrations

Accepted Flyway V1-V8 migrations remain immutable.

Catalog commercial completion continues through forward migration V9.
V6-V8 are accepted history and are not edited.

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

Tenant IDs, Product IDs, Variant IDs, SKUs and external identifiers must not
become unbounded metric labels.

Correlation/request IDs remain the diagnostic mechanism for individual request
investigation.

Logs and errors must not expose cross-tenant data, SQL/JDBC details, full catalog
payloads or unnecessary commercial identifiers.

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

ADR-0009 remains `DESIGNED` until at minimum all of the following have executable
evidence:

### Catalog

- [ ] Catalog is recognized as an independent Spring Modulith module.
- [ ] Product and ProductVariant invariants are tested without Spring.
- [ ] Category hierarchy and multi-category assignment are tenant-safe.
- [ ] same-tenant SKU uniqueness is deterministic.
- [ ] standardized identifiers are optional and cannot be fabricated by default.
- [ ] money uses ISO 4217 currency plus exact integer minor-unit storage.
- [ ] media metadata cannot create an implicit arbitrary-URL server-fetch path.
- [ ] Order-line terminology is migrated from Product identity to Variant identity.

### Inventory

- [ ] Inventory is recognized as an independent Spring Modulith module.
- [ ] Position, Policy and Commitment invariants are tested without Spring.
- [ ] Inventory references sellable Variant identity.
- [ ] missing Position/Policy fails closed.
- [ ] `DENY` cannot oversell under real PostgreSQL concurrency.
- [ ] `ALLOW_BACKORDER` produces correct allocated/backordered totals under
      concurrent demand.
- [ ] duplicate Variant lines aggregate deterministically.
- [ ] tenant A Inventory can never mutate tenant B Inventory.

### Transaction and failure behavior

- [ ] Order + Inventory share one proven physical transaction.
- [ ] Order persistence failure leaves Inventory unchanged.
- [ ] Inventory failure after Order insert rolls Order back.
- [ ] multi-item partial failure produces zero durable business effects.
- [ ] deterministic Variant ordering is covered by inverse-order concurrency
      tests.
- [ ] finite lock waiting is covered by a deliberate blocking test.
- [ ] correctness is proven across more than one application replica/process
      boundary where the platform test is meaningful.

### Persistence and architecture

- [ ] V1-V5 remain byte-for-byte unchanged.
- [ ] V6+ migrations build Catalog/Inventory from an empty real PostgreSQL
      database.
- [ ] PostgreSQL constraints reject impossible state when application validation
      is bypassed.
- [ ] no cross-module persistence implementation imports/FKs are introduced.
- [ ] Spring Modulith verification remains green.

### Regression / repository gates

- [ ] existing Orders, Tenants, Users and Security behavior remains green.
- [ ] `git diff --check` passes.
- [ ] `.\mvnw.cmd clean verify` passes.
- [ ] `branch-policy`, `ci-build` and `platform-validation` pass on the remote PR.

Only after the complete verification evidence exists may this ADR be promoted to
`TESTED`.

## Follow-up

The approved evolution roadmap is recorded in `docs/ROADMAP.md`.

In particular, Catalog/Inventory administration remains behind a later
fine-grained authorization boundary; durable request idempotency precedes broad
automatic retry/recovery; transactional outbox precedes reliable outbound
webhooks; and advanced PIM/pricing/warehouse capabilities require concrete new
business requirements before implementation.

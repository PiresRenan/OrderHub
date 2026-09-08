# Catalog and Inventory administration HTTP contract

OH-018 is Tenant business administration. Every endpoint requires bearer authentication,
an internal User, an exact active Tenant membership selected by `X-Tenant-Id`, and
the existing current Staff authorization decision. The header is a reconciled selector,
never authority by itself. JWT roles, Customer relationships and Platform/Organization
grants do not grant these permissions. Body fields cannot select actor or Tenant.

## Catalog

| Method and path | Permission | Body / result |
| --- | --- | --- |
| POST `/catalog/products` | CATALOG_MANAGE | `id,name,slug,description,brand`; creates DRAFT, 201 |
| PUT `/catalog/products/{id}/metadata` | CATALOG_MANAGE | `expectedRevision,name,slug,description,brand` |
| POST `/catalog/products/{id}/activate` or `/archive` | CATALOG_MANAGE | `expectedRevision` |
| PUT `/catalog/products/{id}/categories` | CATALOG_MANAGE | `expectedRevision,categoryIds` (at most 100 distinct owned IDs) |
| POST `/catalog/products/{id}/variants` | CATALOG_MANAGE | `id,sku,displayName,gtin,mpn,attributes`; creates DRAFT, 201 |
| PUT `/catalog/variants/{id}/metadata` | CATALOG_MANAGE | `expectedRevision,sku,displayName,gtin,mpn,attributes` |
| POST `/catalog/variants/{id}/activate`, `/deactivate` or `/archive` | CATALOG_MANAGE | `expectedRevision` |
| POST `/catalog/categories` | CATALOG_MANAGE | `id,parentCategoryId,name,slug,description`; 201 |
| PUT `/catalog/categories/{id}/metadata` | CATALOG_MANAGE | `expectedRevision,name,slug,description` |
| PUT `/catalog/categories/{id}/parent` | CATALOG_MANAGE | `expectedRevision,parentCategoryId`; null parent means root |
| PUT `/catalog/variants/{id}/prices/{currency}` | CATALOG_PRICE_MANAGE | `expectedRevision,minorUnits` |
| GET `/catalog/products/{id}`, `/catalog/variants/{id}`, `/catalog/categories/{id}` | CATALOG_VIEW | Current resource with revision |
| GET `/catalog/variants/{id}/prices/{currency}` | CATALOG_VIEW | `variantId,currencyCode,minorUnits,revision` |
| GET `/catalog/products`, `/catalog/products/{id}/variants`, `/catalog/categories` | CATALOG_VIEW | Bounded array, optional `afterId`, `limit` |

All successful changes other than creation return 200 and the resulting resource.
Revisions start at 1. Price creation uses expected revision 0 (absent); updates use
the last observed revision. Stale writes return 409. On a lost response, read the
current revision and reconcile before issuing a new change. Identity and Variant
parent are immutable. Product activation requires an active Variant. Category writes
retain the existing Tenant hierarchy guard. Base prices use canonical recognized
uppercase ISO currency codes and exact nonnegative signed-64-bit minor units.

Names are bounded to 160 code points and descriptions to 4,000. Variants accept at
most 50 bounded `{key,value}` attributes. Existing domain rules additionally validate
slugs, merchant identifiers, commercial lifecycle and attribute semantics.

## Inventory

| Method and path | Permission | Body / result |
| --- | --- | --- |
| POST `/inventory/receipts` | INVENTORY_RECEIVE | `operationId,variantId,quantity,reason`; 201 movement |
| POST `/inventory/adjustments` | INVENTORY_ADJUST | `operationId,variantId,delta,reason`; 201 movement |
| PUT `/inventory/positions/{variantId}/safety-stock` | INVENTORY_POLICY_MANAGE | `expectedSafetyStock,safetyStock,reason`; 200 position |
| PUT `/inventory/policy` | INVENTORY_POLICY_MANAGE | `expectedPolicy,policy,reason`; 200 policy |
| GET `/inventory/policy` | INVENTORY_VIEW | `policy` |
| GET `/inventory/positions/{variantId}` | INVENTORY_VIEW | `variantId,onHand,committed,backordered,safetyStock` |
| GET `/inventory/positions` | INVENTORY_VIEW | Bounded array, optional `afterVariantId`, `limit` |
| GET `/inventory/positions/{variantId}/movements` | INVENTORY_VIEW | Bounded array, optional `afterOperationId`, `limit` |

Receipt quantity is strictly positive. Adjustment delta is signed, nonzero and cannot
reduce physical stock below existing commitments. Quantities use signed-64-bit integers;
the minimum signed value is excluded for adjustments. JSON fractional or overflowing
numbers are rejected without truncation. `reason` is a bounded operational code matching
`[A-Z][A-Z0-9_]{0,63}`, not free-form personal information.

The Tenant-scoped operation UUID is durable retry identity. A retry with the same
actor, Variant, type, delta and reason returns the original movement, including its
original occurrence and server-generated correlation UUID, with 201 and no new stock
effect. Any different intent or actor using that identity returns 409. Current
permission is required even for replay. Movement and stock change commit atomically;
failed acquisition/identity/arithmetic/evidence leaves no partial write.

Safety stock is nonnegative, may exceed uncommitted physical stock and never rewrites
commitments. Policy is only `DENY` or `ALLOW_BACKORDER`; expected null initializes an
absent policy. Repeating an already-current desired safety or policy state returns 200
without another evidence row, including when its old precondition has become stale.
A different desired state requires the actual expected prior value.

## Reads, conflicts and failures

List limits are 1–100, default 50. Cursors use strict PostgreSQL UUID ordering and
exclude the cursor item. These are current-state pages, not a frozen multi-request
export. Movement UUID order is deterministic rather than chronological. Catalog lists
use summaries or flat Category nodes, never recursive trees or per-item attribute loads.

Authorization precedes sensitive reads. Denied callers receive 403 for both absent
and existing targets. Authorized foreign targets behave as absent (404). Missing positions
on reads or safety-stock changes return 404. Adjustments without a mutable position,
and quantity/precondition/operation conflicts, return 409. Invalid input is 400;
missing authentication is 401. Technical
uncertainty is a sanitized 500, never a false permission denial. Framework statuses,
including 406 and 415, retain their meaning. Errors use `application/problem+json`,
stable codes and fixed messages without SQL, raw exception text, credentials or bodies.

Programmatic authoritative transactions have configurable positive timeouts:
`orderhub.catalog.administration.transaction-timeout-seconds` and
`orderhub.inventory.administration.transaction-timeout-seconds`, each defaulting to 5.
Category's established hierarchy transaction bound also remains applicable.

Administration preserves the Variant → Product → policy → position order when those
resources are combined. Policy observations hold shared locks through Order commitment.
Inventory arithmetic remains conditional SQL. An Order may reject an insufficient
visible balance while a receipt is still uncommitted; pending receipt stock is not a
promise. Once committed, new Order attempts can consume it. Existing commitments are
never retroactively recalculated by a policy or safety change.

Catalog audit facts and Inventory movements/policy evidence are owner-local and
append-only, including protection against TRUNCATE. Catalog evidence contains revision
transitions and exact financial before/after values where relevant; Inventory movements
are the stock operation's own authoritative evidence. No additional analytics feed,
metric labels containing IDs, generic audit framework or warehouse domain is added.

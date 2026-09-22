# System usage guide

OrderHub is a B2B multi-tenant backend for catalog and inventory administration, Organization and Tenant
administration, identity and membership lifecycle, and Customer-owned Orders. This guide shows how to exercise the
implemented v1 surface end to end, using the disposable development environment. It is a usage guide: the authoritative
contract is the [generated OpenAPI](../api/README.md), the trust rules are in the [security guide](../security/README.md)
and the runtime settings are in [configuration](../operations/configuration.md).

The admitted v1 surface has 60 public business operations. Existing paths have no `/v1` prefix. Health and
documentation endpoints are separate from the business API. External authentication never grants business authority by
itself: the server resolves the internal User, the selected Tenant, membership, ownership and current permissions.

## Prerequisites

Java 21, a running Docker Linux engine and Git. The Maven Wrapper is in the repository. No local PostgreSQL, no `.env`
and no external identity provider are needed for development.

## Start the disposable environment

```powershell
.\mvnw.cmd -B spring-boot:test-run "-Dspring-boot.run.main-class=io.github.piresrenan.orderhub.development.LocalDevelopmentApplication"
```

| Service | Address |
| --- | --- |
| API | `http://127.0.0.1:8080` |
| Synthetic issuer (loopback only) | `http://127.0.0.1:9090` |
| Swagger UI | `http://127.0.0.1:8080/swagger-ui/index.html` |
| Generated OpenAPI | `http://127.0.0.1:8080/v3/api-docs` |
| Fixture manifest | `http://127.0.0.1:9090/fixture` |

The launcher owns a new PostgreSQL container, runs the migrations, applies the seed through the real application use
cases and publishes the manifest only after every step succeeded. `Ctrl+C` stops everything and discards the database.
Restarting rebuilds the same logical scenario. The launcher, issuer and seed exist only on the test classpath; see
[development runtime](../development/local-runtime.md) and [seed data](../development/seed-data.md).

## The seeded dataset

| Entity | Count |
| --- | ---: |
| Organizations | 3 |
| Tenants | 5 |
| Personas | 13 |
| Memberships | 11 |
| Staff | 7 |
| Customers | 6 |
| Categories | 9 |
| Products | 11 |
| Variants | 17 |
| Prices | 15 |
| Inventory positions | 8 |
| Inventory movements | 11 |
| Orders | 4 |
| Outstanding proof / intent | 2 |

Products cover DRAFT, ACTIVE and ARCHIVED; Variants cover all four states; prices exist in BRL and USD; Tenant alpha
uses the DENY policy and Tenant beta uses ALLOW_BACKORDER; the four Orders cover all three allocation outcomes.
Equivalent SKUs and slugs exist deliberately in different Tenants, because those uniqueness rules are Tenant-scoped.

The 13 personas and their exact authority are tabulated in [seed data](../development/seed-data.md). Never infer a UUID
from a persona name: read identifiers from the manifest.

## Read the manifest and get a token

```powershell
$fixture = Invoke-RestMethod http://127.0.0.1:9090/fixture
$staff = (Invoke-RestMethod -Method Post -Uri http://127.0.0.1:9090/tokens/staff -ContentType application/json).access_token
$alpha = ($fixture.tenants | Where-Object key -eq 'alpha').id
```

The manifest publishes only synthetic, non-secret selectors: personas, organizations, tenants, customers, catalog,
inventory, orders and outstanding proof/intent identifiers. It never contains bearer tokens, one-time credentials,
signing keys or database passwords. Tokens live five minutes; request a new one when it expires. An unknown persona
returns 404. These issuer endpoints are not part of the product API.

## Request headers

Orders, Catalog and Inventory require the Tenant selector:

```http
Authorization: Bearer <access-token>
X-Tenant-Id: <tenant-uuid>
```

Creating an Order additionally requires an idempotency identity:

```http
Idempotency-Key: <1-128 visible ASCII characters (0x21-0x7E), no comma, no whitespace>
```

Administrative routes that already carry `{tenantId}` in the path do not need the header.

## Health

`GET /livez`, `GET /readyz` and `GET /actuator/health` return `{"status":"UP"}` when healthy; only `health` is exposed
through Actuator. With PostgreSQL unavailable, liveness can stay 200 while readiness and database-backed calls fail.
Health is an operational signal, not proof that authentication or a business operation works.

## Catalog

Read as Staff of the Tenant that owns the resource:

```powershell
$headers = @{ Authorization = "Bearer $staff"; 'X-Tenant-Id' = $alpha }
Invoke-RestMethod -Uri "http://127.0.0.1:8080/catalog/products/$($fixture.productId)" -Headers $headers
```

Expected: authorized Staff 200; a Customer or a member without the permission 403; another Tenant's resource 404 for an
authorized caller; a missing, invalid or unbound bearer 401 on normal routes.

Creating a Product:

```http
POST /catalog/products
{ "id": "<uuid>", "name": "Demonstration product", "slug": "demonstration-product",
  "description": "Created for development", "brand": "OrderHub Demo" }
```

A valid request returns 201 with the Product in DRAFT; invalid input 400; missing `CATALOG_MANAGE` 403; a duplicate
slug in the same Tenant 409 (the same slug in another Tenant is legal).

**Lifecycle.** Mutations carry `expectedRevision` and a stale revision returns 409. A Variant starts in DRAFT;
activating a Product requires at least one eligible active Variant, otherwise 409. ARCHIVED is terminal for Products
and Variants: activating an archived resource returns 409. INACTIVE Variants can be activated again. After an uncertain
response, re-read the resource before mutating again.

**Categories.** `PUT /catalog/categories/{id}/parent` moves a Category. A cycle is rejected with 409. A parent from
another Tenant and a parent that never existed both return 409 and change nothing, so the response cannot be used to
detect existence.

**Prices.** `PUT /catalog/variants/{id}/prices/{currency}` creates with `expectedRevision: 0` and updates with the
observed revision. `minorUnits` is an exact signed 64-bit integer: 1290 BRL means BRL 12.90. Reading an absent currency
returns 404 and an unrecognized currency code is rejected with 400.

## Inventory

```powershell
$body = @{ operationId = [guid]::NewGuid().ToString(); variantId = $variantId; quantity = 5; reason = 'LOCAL_RECEIPT' } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8080/inventory/receipts -Headers $headers -ContentType application/json -Body $body
```

The first call returns 201. An exact replay of the same `operationId` returns 201 and produces no second stock effect.
The same `operationId` with different content returns 409. A Customer receives 403. Adjustments use a non-zero `delta`,
may be negative, and are rejected with 409 when they would violate stock invariants.

Policy and safety stock are desired-state writes: requesting the value already in force succeeds with 200 even when the
supplied expectation is stale, while a real change with a stale expectation returns 409. The Tenant policy is `DENY` or
`ALLOW_BACKORDER`.

## Orders

```powershell
$customer = (Invoke-RestMethod -Method Post -Uri http://127.0.0.1:9090/tokens/customer -ContentType application/json).access_token
$key = "local-order-$([guid]::NewGuid())"
$orderHeaders = @{ Authorization = "Bearer $customer"; 'X-Tenant-Id' = $alpha; 'Idempotency-Key' = $key }
$order = @{ customerId = $fixture.customerId; items = @(@{ variantId = $fixture.variantId; quantity = 2 }) } | ConvertTo-Json -Depth 5
Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8080/orders -Headers $orderHeaders -ContentType application/json -Body $order
```

A valid request returns 201 with `allocationOutcome` FULLY_ALLOCATED, PARTIALLY_BACKORDERED or FULLY_BACKORDERED.
Replaying the same key with the same body returns 201 with the same Order and reserves nothing again. The same key with
different content returns 422. A concurrent acquisition of the same key returns 409, as does insufficient stock under
the DENY policy. Items above the technical maximum return 413. Staff cannot create a Customer's Order (403).

`GET /orders/{orderId}` returns 200 for the owning Customer. Anything that is not the caller's own Order — another
Customer's Order, a Staff caller, or an absent identifier — returns 404. A caller with no membership in the selected
Tenant is refused earlier with 403.

## Identity and lifecycle

**Initial Staff.** `POST /administration/tenants/{tenantId}/initial-staff-provisioning` issues a one-time proof to an
authorized Platform caller: the first response carries `intentId`, `credential` and `expiresAt`; a replay of the same
operation returns only `intentId`. A verified identity that is not yet bound consumes it at
`POST /identity/bootstrap/staff` with `{"credential":"..."}` and receives `{"id":"<staff-id>"}`. Reuse and cancelled
proofs are refused. Cancellation uses `DELETE .../initial-staff-provisioning/{intentId}` and answers `{"changed":true|false}`.

**Normal Staff provisioning.** `POST /administration/tenants/{tenantId}/staff-provisioning` requires `departmentId` and
`positionId`; the development manifest publishes the Tenant's `staffPlacement` because v1 exposes no read for it. An
`initialRoleCode` may name the single v1 Staff role, `INITIAL_TENANT_GOVERNANCE_V1`; omitting it creates a membership
with no Tenant authority.

**Customer linking.** The CustomerProfile must already exist. Authorized Staff issues a proof at
`POST /administration/tenants/{tenantId}/customers/{customerId}/account-link-proofs`; a bound User consumes it at
`POST /tenants/{tenantId}/customer-account-links` and receives `{"id":"<customer-id>"}`. Linking grants the Customer
relationship, never Staff authority.

**External identity.** A bound User issues a proof at `POST /identity/external-link-proofs`; a not-yet-bound verified
identity consumes it at `POST /identity/bootstrap/external-links`, and both identities then resolve to the same
internal User. `GET /identity/external-accounts` lists the caller's own bindings and `DELETE /identity/external-accounts/{bindingId}`
removes one; removing the last remaining binding is refused. Acting on another User's proof or binding is a 200 no-op
with `changed:false`, identical to an absent identifier.

**Membership.** `POST /administration/tenants/{tenantId}/memberships/{subjectId}/suspend`,
`POST /administration/tenants/{tenantId}/memberships/{subjectId}/recover` and
`POST /administration/tenants/{tenantId}/memberships/{subjectId}/terminate`
answer `{"changed":true}`, or `{"changed":false}` when the state already matches. ACTIVE and SUSPENDED convert into each
other, both can be TERMINATED, and TERMINATED is terminal. Self-transitions are denied. Identity-lifecycle refusals use
one uniform 403 so they cannot be used to probe internal state.

## Platform and Organizations

Platform grants administer Organizations, Tenants and placements: create and list Organizations, suspend and recover
them, create Tenants, suspend and recover Tenants, and attach, move or detach a Tenant placement. Repeating a
suspension or an attachment is idempotent (204). A second Organization for an already-placed Tenant, a move from the
wrong source and a detach from the wrong Organization return 409. `ORGANIZATION_TENANTS_VIEW` lets a User list one
Organization's Tenants; without the grant the Organization answers 404 rather than revealing that it exists. Platform
authority never grants Tenant-private Catalog, Inventory or Order access (403).

## Multi-tenancy

| Scenario | Result |
| --- | --- |
| Actor in A with resource in A | as permissions allow |
| Actor in A with resource in B | denied or absent, per the owner contract |
| Listing in A | never returns B |
| Mutation in A | never alters B |
| Customer A with Customer B's Order | 404, non-enumerating |
| Multi-Tenant Staff | authority follows the explicitly selected Tenant |
| Same SKU or slug in A and B | no collision; uniqueness is Tenant-scoped |

The Tenant is never inferred from a JWT claim.

## Pagination

`GET /catalog/categories`, `/catalog/products`, `/catalog/products/{productId}/variants`, `/inventory/positions` and
`/inventory/positions/{variantId}/movements` use UUID cursors: `limit` is 1..100 with a default of 50, and the cursor
(`afterId`, `afterVariantId` or `afterOperationId`) is exclusive. UUID order is deterministic but not chronological,
there is no total count and pages are not a frozen snapshot. Other list operations return their full authorized set.

## Errors

Errors use `application/problem+json` with `type`, `title`, `status`, optional `detail`, optional owner-defined `code`
and, for supported Bean Validation failures, an `errors` array of `field`, `code` and `message`.

| Status | Meaning |
| ---: | --- |
| 400 | invalid request metadata or body |
| 401 | missing/invalid bearer, or a verified identity with no active internal binding |
| 403 | authority, membership, ownership or proof policy refused |
| 404 | absent, or existence deliberately hidden |
| 405 / 406 / 415 | method, representation or media type not supported |
| 409 | state, revision, quantity or operation conflict |
| 413 | Order exceeds the technical item limit |
| 422 | Order idempotency key reused with different content |
| 500 | sanitized technical failure |
| 503 | identity resolution temporarily unavailable; honor `Retry-After` |

Clients branch on the status and `code`, never on `detail` text.

## Reset and production

Stop and restart the launcher to reset; it always builds a fresh database. Never edit the database by hand as a normal
workflow. The seed is not a production provisioning mechanism: production requires its own PostgreSQL and JWT trust
settings, the correct audience, an explicit token profile and, under Cognito, an App Client allowlist, as described in
[configuration](../operations/configuration.md) and [client integration](../integration/README.md). A dedicated gate
rejects any development fixture class, resource or marker in the production artifact.

## Deliberate v1 limitations

Independent machine-to-machine or client-credentials principals, API keys, public self-service registration, password
services, payment settlement, fulfillment, warehouse reservation services, public brokers or webhooks, full Workforce
role and position CRUD, and generic CustomerProfile creation over HTTP are **not** v1 capabilities. Do not simulate
them in a client; see [frontend integration](frontend-integration.md) and [application integration](application-integration.md).

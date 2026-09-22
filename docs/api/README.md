# Using the HTTP API

The admitted contract has 60 explicit operations in six controllers. Paths are
the existing `/orders`, `/catalog`, `/inventory`, `/platform`, `/organizations`,
`/identity`, `/administration` and `/tenants` routes; there is no `/v1` URI
prefix. Framework health endpoints are separate from the business API.

OpenAPI is generated at runtime from MVC handlers, DTO metadata and narrow
schema customization. `OpenApiContractTest` compares actual handler mappings,
operation IDs, important schemas, responses and security with the generated
artifact. `target/contracts/openapi.json` is build output, not an independent
hand-maintained specification.

## Swagger and authentication

For task-oriented walkthroughs of these operations see the [system usage guide](../guides/system-usage.md); for
client-side contracts see the [frontend](../guides/frontend-integration.md) and
[application](../guides/application-integration.md) integration guides.

Start the [explicit local launcher](../development/local-runtime.md), then open
[local Swagger UI](http://127.0.0.1:8080/swagger-ui/index.html). Retrieve a token
for the appropriate synthetic persona from the loopback issuer, paste the token
into **Authorize**, and supply selectors for the chosen operation. Swagger's
bearer input expects the token alone. The generated contract and UI use the
current deployment rather than a remote validator or hard-coded public server.
Authorization persistence is disabled; clear the value when finished.

Normal business endpoints require a verified bearer token bound to an active
internal User. Issuer, audience, signature and time claims are validated.
Provider roles and Tenant claims do not grant business authority. The two
`/identity/bootstrap/*` operations also verify bearer tokens but deliberately
allow an externally verified identity that does not yet have an internal binding;
an independently issued, one-time proof supplies the business relationship.

Orders, Catalog and Inventory require exactly one `X-Tenant-Id` UUID selector.
The server independently proves active membership and active Tenant state.
Lifecycle/administrative paths select a Tenant through the path and perform
owner-specific authorization; do not add a fictional required header there.
Platform/Organization grants do not imply access to private Tenant business data.

Runtime documentation defaults to disabled, including production and staging.
The `dev` profile enables convenient documentation access; mixed production or
staging profiles do not receive the anonymous development filter. Deliberately
enabling runtime docs elsewhere retains the normal internal-user bearer chain.
Swagger and Actuator exposure are separately configured.

## Operations and representations

| Family | Operations | Authority / focused contract |
| --- | --- | --- |
| Orders | 2 | Customer binding plus CUSTOMER_ORDERS_CREATE/VIEW and ownership |
| Catalog | 21 | Current Staff CATALOG_VIEW/MANAGE/PRICE_MANAGE; [detailed contract](../catalog-inventory-administration-http.md) |
| Inventory | 8 | Current Staff INVENTORY_VIEW/RECEIVE/ADJUST/POLICY_MANAGE; same focused guide |
| Platform/Organization | 13 | Explicit administrative grants; no implicit Tenant business access |
| Identity/Tenant lifecycle | 14 | Internal account ownership or current authorized management; [focused contract](../oh019-http-contract.md) |
| Unbound identity bootstrap | 2 | Verified external identity and a valid owner-issued one-time proof |

Requests and successful bodies use JSON. Body-less 204 responses have no JSON
payload. Created Catalog/administrative resources return Location when their
controller emits it; Order and Inventory creation do not invent one. UUIDs are
opaque selectors. No ETag or If-Match protocol is implemented; Catalog revisions
and Inventory expected-state fields carry their actual concurrency contracts.

Catalog prices use `currencyCode` plus exact nonnegative signed-64-bit integer
`minorUnits`. For example, `1250` BRL means BRL 12.50. JSON numbers must be
processed with an exact integer/decimal library; JavaScript numbers cannot
represent every signed-64-bit integer. Do not round them through binary floating
point or silently stringify response values. Inventory quantities are integral
signed-64-bit values subject to stock invariants; Orders line quantities are
positive int32. Fractional/overflowing mutations are rejected rather than
truncated. There is no price total or payment amount in an Order response.

Enums and lifecycle states are enumerated in OpenAPI. Timestamps carry an offset
or UTC `Z`; Inventory movement occurrence is persisted at microsecond precision
and replay retains it. There is no LocalDate request contract. Currency, GTIN,
SKU, attribute and normalization descriptions reflect owner validation, including
constraints that cannot be expressed by a simple JSON Schema length or pattern.

## Retry, concurrency and paging

`POST /orders` requires one `Idempotency-Key`: 1–128 visible ASCII characters,
excluding comma and whitespace. Store the key alongside the logical request.
The server keeps only its digest, scoped by trusted Tenant and CREATE_ORDER_V1.
Identical canonical intent replays the original 201 result after current
ownership is rechecked; changed content uses 422. Acquisition still in progress
uses 409. Reattempt an uncertain original request with the same key/content
after allowing it to settle; do not create a new key to bypass uncertainty.
No automatic expiry or Retry-After header is promised. Item order/multiplicity
is part of the canonical command.

Inventory receipt/adjustment uses `operationId` in the body, scoped by Tenant.
Same actor/intent returns the original 201 movement without another stock
effect; changed intent or actor returns 409. Catalog writes use observed
revisions and require read/reconciliation after an uncertain response. Inventory
policy/safety desired-state writes can return the already-current state without
another evidence row. These mechanisms are distinct; a generic retry wrapper
must not assume every POST has the Orders header contract.

Catalog and Inventory lists return arrays with `limit` 1–100, default 50.
Use the last identifier as the next exclusive `afterId`, `afterVariantId` or
`afterOperationId` cursor named by that endpoint. PostgreSQL UUID ordering is
deterministic, not chronological; pages are not a frozen export and concurrent
changes may affect later reads. A shorter page ends the current traversal.
There is no total-count, offset, arbitrary sort or filter API. Organization and
external-account arrays are not cursor-paged; their OpenAPI descriptions say so.

## One-time credentials and errors

Issued Staff, Customer and external-link proof responses contain an identifier,
`credential` and `expiresAt`; a replay contains only the identifier. There is no
discriminator property. Store/deliver the first credential securely. An uncertain
issuance response cannot be repaired by retrieving a stored secret: cancel the
pending proof when authorized and issue under a new operation identity.
Successful lifecycle responses use `Cache-Control: no-store`. Staff TTL is
configured (default 30 minutes); Customer/external-link TTL is 15 minutes.

Application and authentication error bodies use `application/problem+json` with RFC 9457 fields and owner
machine-readable `code` when provided. Validation errors may include field/code/
fixed-message entries, never rejected values. Clients should branch on status
and documented codes, not parse English messages or infer target existence.

Rejected CORS preflights/origins are transport admission failures handled by
Spring's CORS processor and need not have a Problem Details body. A browser
cannot read a response without origin admission. Allowed-origin application
401/403 responses retain their normal Problem Details and CORS headers. See
[direct browser integration](../integration/README.md).

| Status | Actual meaning |
| --- | --- |
| 400 | Malformed JSON, missing/invalid metadata or input validation |
| 401 | Missing/invalid bearer or inactive/unbound normal identity; bounded Bearer challenge |
| 403 | Current owner/relationship/permission or proof policy denies the operation |
| 404 | Unavailable/foreign target; some administrative/Customer reads deliberately conceal existence |
| 405 / 406 / 415 | Unsupported method / response representation / request media type |
| 409 | Authorized state/precondition/quantity conflict or unresolved idempotency acquisition |
| 413 | Structurally valid Order exceeds the configured technical item-count limit |
| 422 | Orders idempotency key reused for different canonical content |
| 500 | Sanitized technical uncertainty; do not reinterpret as authorization denial |
| 503 | Identity verification is temporarily unavailable; bounded retry guidance, not an instruction to replace valid credentials |

429 is not an implemented application rate-limit contract. A deployment proxy
may have its own errors, which must be configured/documented separately. Error
messages never reveal SQL, internal exception types, JWT contents or proofs.

See the development guide for executable PowerShell token, Tenant, stock and
Order/replay commands. The same flow can be exercised in Swagger with the
matching persona. [Security](../security/README.md) explains the trust model;
[operations](../operations/README.md) explains health, failures and recovery.

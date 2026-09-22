# Application integration guide

Audience: desktop and native mobile applications, CLIs, server-side applications and BFFs acting for a delegated human
user, internal tools and administrative integrations. The central v1 limit is stated up front: **OrderHub has no
independent machine identity.** There is no `client_credentials` principal, no API key, no workload identity, no magic
header and no conversion of OAuth scopes into Staff permissions. An integration that needs its own service identity is
a post-v1 requirement.

The contract is the [generated OpenAPI](../api/README.md); trust rules are in the [security guide](../security/README.md)
and runtime inputs in [configuration](../operations/configuration.md).

## Choosing an integration model

**Direct human application** (SPA, mobile, desktop, user-authenticated CLI): use OAuth2/OIDC Authorization Code with
PKCE where the provider supports it, and call the API with the user's access token.

**Backend or BFF for a user**: allowed when the backend acts for a legitimately delegated human user. Being
server-side grants no additional authority: User binding, Tenant selection, membership, ownership and permissions still
decide every call. A BFF owns its own session security, CSRF, cookie policy, refresh and logout, and must not replace
OrderHub permissions with local roles.

**Autonomous service**: not supported in v1 as an independent principal. Do not reuse a person's token for a daemon,
invent an API key or a header, or model a service permission lifecycle; classify the need as post-v1.

## Authentication and Tenant selection

Every protected call sends `Authorization: Bearer <access-token>` with an access token, not an ID token. Production
validates the signature, exact issuer, audience, expiry and token profile; under Cognito it also requires
`token_use=access` and an allowlisted `client_id`.

Orders, Catalog and Inventory require an explicit `X-Tenant-Id`. Never take the Tenant from a provider claim.
Administrative routes carry `{tenantId}` in the path and need no header.

## Retry contracts differ per family

| Family | Retry identity | Behavior |
| --- | --- | --- |
| Orders | `Idempotency-Key` | exact replay returns 201 with the original Order |
| Inventory receipt / adjustment | `operationId` | exact replay returns 201 with no second effect |
| Proof and intent issuance | `operationId` (+ correlation) | replay returns the identifier without the credential |
| Catalog mutation | `expectedRevision` | not a replay: reconcile, then mutate again |
| Inventory desired state | expected policy / safety stock | the value already in force returns 200 |

Never apply one blanket retry rule to all of them.

## Orders

```http
POST /orders
Authorization: Bearer <customer-token>
X-Tenant-Id: <tenant>
Idempotency-Key: order-2026-09-22-001
Content-Type: application/json

{ "customerId": "<uuid>", "items": [ { "variantId": "<uuid>", "quantity": 2 } ] }
```

| Status | Meaning |
| ---: | --- |
| 201 | created, or replay of the same intent |
| 400 | invalid header or body |
| 401 | invalid authentication or no active binding |
| 403 | Customer, Tenant or authority not admitted |
| 409 | concurrent acquisition of the key, or a business conflict such as insufficient stock under DENY |
| 413 | more items than the technical maximum |
| 422 | same key with different content |
| 500 / 503 | sanitized technical failure / identity resolution unavailable |

After a timeout, repeat with the same key and body. Persist the idempotency identity alongside the business intent if
the process can restart.

## Inventory and Catalog

Receipts and adjustments carry a client `operationId`; `delta` must be non-zero and may be negative, and a request that
would violate stock invariants returns 409. Policy is `DENY` or `ALLOW_BACKORDER`; policy and safety stock are
desired-state writes where the current value returns 200 and a real change with a stale expectation returns 409.

Catalog mutations are revision based:

```text
read -> keep revision -> mutate with expectedRevision -> on 409 read again -> reconcile -> mutate deliberately
```

Prices are exact integers, `currencyCode` plus `minorUnits` (1250 BRL is BRL 12.50). Never use floating point for
money, and never round a 64-bit quantity into a narrower type.

## Pagination

Catalog categories, products and variants, and Inventory positions and movements use UUID cursors:

```http
GET /catalog/products?limit=50
GET /catalog/products?limit=50&afterId=<last-id>
```

`limit` is 1..100 with a default of 50, the cursor is exclusive, ordering is deterministic but not chronological, and
there is no offset and no total count. Pages are not a snapshot, so an export must tolerate concurrent change between
pages. Other list operations return their full authorized set.

## Identity workflows

**Initial Staff.** An authorized Platform caller issues the one-time proof; a verified identity that is not yet bound
consumes it at `POST /identity/bootstrap/staff`. An issuance replay never returns the credential again.

**Normal Staff provisioning.** Requires an existing `departmentId` and `positionId` for the Tenant. v1 can assign only
the single governance role `INITIAL_TENANT_GOVERNANCE_V1`, or no role at all; full Workforce role and position CRUD is
post-v1.

**Customer linking.** The CustomerProfile must exist, authorized Staff issues the proof and an already-bound User
consumes it. The link grants the Customer relationship and membership, never Staff authority.

**External identity linking.** An internal User issues a proof for an additional sign-in identity, and the new identity
consumes it at `POST /identity/bootstrap/external-links`; both identities then resolve to the same internal User.
Acting on another User's proof or binding is a 200 no-op with `changed:false`, indistinguishable from an absent
identifier. Removing a User's last remaining binding is refused.

**Membership.** ACTIVE and SUSPENDED convert into each other, both can become TERMINATED, and TERMINATED is terminal.
Repeating a transition answers `{"changed":false}`, self-transitions are denied, and identity-lifecycle refusals use a
uniform 403 that does not reveal internal state.

## Multi-tenancy

Test all three combinations for every Tenant-scoped feature:

```text
Tenant A actor + Tenant A resource
Tenant A actor + Tenant B resource
Tenant B actor + Tenant B resource
```

Never cache by a business key alone when uniqueness is Tenant-scoped; key caches by `tenantId + resourceType +
resourceId`. The same SKU or slug legitimately exists in different Tenants.

## Native, CLI and BFF notes

Native applications use the appropriate OAuth/OIDC flow with PKCE, do not depend on CORS, still send the access token
and still select the Tenant explicitly; redirect and deep links must be registered with the provider, and a public app
client embeds no secret. A user CLI authenticates the person, obtains the access token, selects the Tenant and calls
the same API; in development it can take a persona token from the loopback issuer, which is not a production mechanism.

## Errors and availability

Problem Details look like `{"type":"urn:orderhub:problem:<code>","title":"...","status":409,"detail":"...","code":"..."}`.
Branch on the status and `code`; never parse `detail`. A 404 does not prove physical absence: it also protects
ownership and prevents enumeration.

A 503 can mean identity resolution is temporarily unavailable. Honor `Retry-After` when present, use bounded backoff
and preserve the write's idempotency identity. Do not convert a technical failure into a new business intent.

## Health is not an authenticated smoke test

`/livez`, `/readyz` and `/actuator/health` are operational signals. A 200 from `/readyz` does not prove JWK reachability
during a real verification, token acceptance, a User's permissions or that a business operation succeeds. Keep a
separate authenticated smoke check.

## Scenarios

| Scenario | Supported | Notes |
| --- | --- | --- |
| ERP operated by a human Staff user | yes | acts as that User; may read Catalog, receive or adjust stock and change policy as permissions allow |
| Customer mobile application | yes | authenticate, select Tenant, create and view its own Orders; no Inventory administration, no other Customer's Orders |
| Platform administration tool | yes | with Platform grants; Platform authority never implies Tenant-private access |
| Nightly job with no user | **no** | v1 has no workload identity; post-v1 |
| Payment settlement integration | **no** | no v1 payment capability; do not build a webhook pretending the contract exists |

| Situation | Expected |
| --- | --- |
| missing or invalid bearer | 401 |
| verified identity, unbound, normal route | 401 |
| bound User without authority | 403 |
| owner-protected Order of another Customer | 404 |
| stale optimistic write | 409 |
| exact Inventory replay / divergent `operationId` | 201 / 409 |
| exact Order replay / same key with different body | 201 / 422 |
| invalid payload / invalid media type | 400 / 415 |
| identity dependency unavailable / unclassified failure | 503 / 500 |

## Pre-production checklist

Correct issuer, JWK URI and audience; explicit token profile; Cognito App Client allowlist; TLS; CORS only for
browsers; validated Tenant mapping; a per-operation retry matrix; persisted idempotency identity where a process can
restart; exact integer handling; conflict reconciliation; logs without tokens or proofs; Problem Details handled;
cross-Tenant tests; and no dependency on the development issuer or seed.

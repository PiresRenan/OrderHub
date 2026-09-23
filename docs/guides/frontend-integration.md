# Frontend integration guide

Audience: SPAs, mobile web, hybrid applications and browser-facing BFFs. The principle is that external
authentication and business authority are separate responsibilities: Cognito or another configured provider
authenticates a person, and OrderHub decides the internal User, the Tenant, membership, ownership and permissions.
This guide is client-facing; the trust rules are specified in the [security guide](../security/README.md), the
deployment inputs in [configuration](../operations/configuration.md) and the provider setup in
[client integration](../integration/README.md).

## Production authentication

With Amazon Cognito use the Authorization Code grant with PKCE S256 from a public app client, with no client secret in
the browser. Send the **access token**, never the ID token. The resource-bound audience identifies the API. Typical
backend configuration:

```text
ORDERHUB_SECURITY_JWT_ISSUER=https://cognito-idp.<region>.amazonaws.com/<pool-id>
ORDERHUB_SECURITY_JWT_JWK_SET_URI=https://cognito-idp.<region>.amazonaws.com/<pool-id>/.well-known/jwks.json
ORDERHUB_SECURITY_JWT_AUDIENCE=https://api.example.com
ORDERHUB_SECURITY_JWT_TOKEN_PROFILE=COGNITO
ORDERHUB_SECURITY_JWT_ALLOWEDCLIENTIDS=<spa-app-client-id>,<mobile-app-client-id>
```

Under the `COGNITO` profile the server requires a valid signature, the exact issuer, a present and valid `exp`, the
exact resource audience, `token_use=access` and a `client_id` listed in that issuer's allowlist. `aud` identifies the
Resource Server and `client_id` the admitted App Client; neither is business authority, and provider scopes or groups
never become Staff, Customer or Platform permissions.

## CORS and token custody

Cross-origin browser access is opt-in and exact:

```text
ORDERHUB_SECURITY_CORS_ALLOWED_ORIGINS=http://localhost:5173
```

Only exact HTTPS origins, or literal HTTP loopback origins in development, are admitted. There is no wildcard, no
`null` origin and no cookie credentials: send `credentials: 'omit'`. Admitted request headers are `Authorization`,
`Content-Type`, `X-Tenant-Id`, `Idempotency-Key` and `Accept`; `Location`, `WWW-Authenticate` and `Retry-After` are
exposed to scripts. Admitted methods are GET, HEAD, POST, PUT and DELETE.

Keep short-lived access tokens in memory. Do not place them in URLs, logs, analytics, error reports or persistent
storage by default. If a BFF holds the token server-side, then session cookies, CSRF, refresh and logout belong to that
BFF, not to OrderHub.

## Authentication states in the UI

Model these separately, because they are different server outcomes:

1. not authenticated;
2. authenticated with the provider but not yet bound to an OrderHub User;
3. bound User without membership in the selected Tenant;
4. member without the required permission;
5. authorized.

On normal routes a 401 does not only mean "token expired": a verified identity with no active internal binding is also
401. Only the two bootstrap routes accept an unbound verified identity.

## Tenant selection

Orders, Catalog and Inventory require `X-Tenant-Id: <uuid>`. The header selects; it never grants. The server validates
the User, membership, Tenant state and permission or ownership on every request. Administrative routes that carry
`{tenantId}` in the path do not need the header.

To offer a Tenant picker, call `GET /tenants?limit=50` with the bearer only. It returns
`{"items":[{"id","name"}],"nextAfterId"}` for Tenants in which the current User holds an ACTIVE membership and
the Tenant is ACTIVE. A page may be short or empty while `nextAfterId` is non-null: keep requesting with
`afterId=<nextAfterId>` until `nextAfterId` is null, and never infer the end from `items.length`. The response is
`Cache-Control: no-store` and is presentation context only. A listed Tenant can be suspended at any time, and the next
Tenant-scoped request is then denied, so do not treat the list as permission (OH-023, ADR-0021).

## Base client

```typescript
type ApiProblem = {
  type: string;
  title: string;
  status: number;
  detail?: string;
  code?: string;
  errors?: Array<{ field?: string; code?: string; message?: string }>;
};

type RequestOptions = {
  method?: string;
  token: string;
  tenantId?: string;
  idempotencyKey?: string;
  body?: unknown;
};

export async function orderHubRequest<T>(apiBase: string, path: string, options: RequestOptions): Promise<T> {
  const headers = new Headers({ Authorization: `Bearer ${options.token}`, Accept: 'application/json' });
  if (options.tenantId) headers.set('X-Tenant-Id', options.tenantId);
  if (options.idempotencyKey) headers.set('Idempotency-Key', options.idempotencyKey);
  if (options.body !== undefined) headers.set('Content-Type', 'application/json');

  const response = await fetch(`${apiBase}${path}`, {
    method: options.method ?? 'GET',
    credentials: 'omit',
    headers,
    body: options.body === undefined ? undefined : JSON.stringify(options.body)
  });

  if (!response.ok) {
    const problem = (await response.json()) as ApiProblem;
    throw { status: response.status, problem };
  }
  if (response.status === 204) return undefined as T;
  return (await response.json()) as T;
}
```

Several fields, including `minorUnits` and stock quantities, are signed 64-bit integers. JavaScript's `Number` cannot
represent that whole range exactly, so use an exact numeric representation where the full range matters and never round
money silently. Money is always `currencyCode` plus integer `minorUnits`: 1290 BRL is BRL 12.90.

## Response matrix

| Status | Interpretation | UI behavior |
| ---: | --- | --- |
| 400 | invalid request | show an input error |
| 401 | invalid token or no active binding | one bounded refresh/reauth decision, never a loop |
| 403 | policy, membership or ownership refused | show access denied |
| 404 | unavailable, or existence hidden | show "unavailable" |
| 409 | state or concurrency conflict | re-read and reconcile |
| 413 | Order too large | reduce items |
| 422 | idempotency key reused with different content | recover the original intent |
| 500 | sanitized technical failure | generic message |
| 503 | identity resolution unavailable | honor `Retry-After`, bounded backoff |

Never turn a 403 or 404 into a statement about who owns a resource or whether it exists.

## Orders

```typescript
const key = crypto.randomUUID();
const body = { customerId, items: [{ variantId, quantity: 2 }] };
const order = await orderHubRequest(apiBase, '/orders', {
  method: 'POST', token: accessToken, tenantId, idempotencyKey: key, body
});
```

A success is 201 and carries `id`, `status`, `customerId`, `tenantId`, `items` and `allocationOutcome`. After a timeout
or an uncertain network result, retry with the **same key, same body and same Tenant**; never mint a new key to "try
again". Possible outcomes are 201 (first execution or replay of the same intent), 409 (concurrent acquisition of the
key, or insufficient stock under a DENY policy) and 422 (same key, different content). A suggested UI state machine:

```text
idle -> submitting -> success
                   -> uncertain -> reconcile with same key/body -> success | conflict
```

## Catalog and Inventory

Catalog mutations are optimistic: read the resource, keep its `revision`, send `expectedRevision`, and on 409 read the
current state, show the conflict and let the user decide. Never silently reapply an edit with a fresh revision.
ARCHIVED is terminal, and activating a Product requires an active Variant.

Inventory receipts and adjustments carry a client `operationId`: the same operation with the same intent returns 201
and no second effect, while the same `operationId` with different content returns 409. Policy and safety stock are
desired-state writes: the value already in force returns 200, and a real change with a stale expectation returns 409.

## Pagination

```text
GET /catalog/products?limit=50
GET /catalog/products?limit=50&afterId=<last-id>
```

Cursor pagination exists on Catalog categories, products and variants, and on Inventory positions and movements:
`limit` is 1..100 (default 50) and the cursor is exclusive. UUID ordering is deterministic but not chronological, there
is no total count, and pages are not a frozen snapshot. A short page ends the current traversal. Other list operations
return their full authorized set.

## One-time credentials

A first issuance may return `proofId`, `credential` and `expiresAt`; a replay returns only `proofId`. Show or hand over
the credential only on that first success, never log or persist it unnecessarily, honor `Cache-Control: no-store` and
do not expect to recover it from a replay.

## Local frontend development

The synthetic issuer deliberately rejects any request carrying a browser `Origin`, so a browser must not call
`http://127.0.0.1:9090/tokens/{persona}` directly. Fetch a token in a terminal and inject it only in development mode:

```powershell
$token = (Invoke-RestMethod -Method Post -Uri http://127.0.0.1:9090/tokens/customer -ContentType application/json).access_token
```

Set the frontend's exact origin in `ORDERHUB_SECURITY_CORS_ALLOWED_ORIGINS` before starting the launcher. The personas
and their expected results are listed in [seed data](../development/seed-data.md).

## Scenarios worth testing

| Scenario | Expected |
| --- | --- |
| Customer creates an Order, then replays the same key and body | 201, then 201 with the same Order |
| Customer calls Inventory administration | 403, no retry loop |
| Expired or invalid bearer | 401, one bounded refresh, then end the session |
| Bound User without access to the selected Tenant | 403 |
| Another Customer's Order | 404, shown as "unavailable" |
| Stale Catalog revision | 409, re-read and reconcile |
| Identity backend unavailable | 503, honor `Retry-After`, keep the idempotency identity |

## Production

Never use the synthetic local issuer outside development. A production frontend contains no Cognito client secret, no
signing key, no AWS credentials and no database credentials. v1 has no independent workload or M2M authority, and a
client must not convert provider scopes into OrderHub permissions; see
[application integration](application-integration.md).

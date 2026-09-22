# Human clients and external integration boundaries

OrderHub is an OAuth2 Resource Server. Cognito authenticates production human
users; OrderHub resolves its own User, membership, persona, role, permission and
resource ownership. These are separate checks. OAuth scopes and Cognito groups
never grant business authority by themselves.

## Browser, mobile and optional BFF

A browser SPA can call the API directly. Register a **public Cognito app client
without a client secret**, exact redirect URIs and authorization-code grant with
PKCE S256. Use a maintained OIDC client to generate/validate state, PKCE and OIDC
nonce where applicable; do not implement password authentication in OrderHub.
Mobile/native clients use the same delegated flow with appropriate registered
platform redirects. CORS applies to browser networking, not native HTTP.

Request the Cognito authorization endpoint with `response_type=code`, the
registered `client_id`, `redirect_uri`, `code_challenge`,
`code_challenge_method=S256`, required scopes, and
`resource=https://api.example.com`. Exchange the returned code with its
`code_verifier` at Cognito's token endpoint. The resource binding makes the
**access token** audience the API URL. Configure that exact URL as OrderHub's
audience; an app-client ID is not the API resource audience. Send the access
token, never the ID token, to OrderHub.

Example deployment inputs (replace placeholders with your actual pool/API):

```text
ORDERHUB_SECURITY_JWT_ISSUER=https://cognito-idp.<region>.amazonaws.com/<pool-id>
ORDERHUB_SECURITY_JWT_JWK_SET_URI=https://cognito-idp.<region>.amazonaws.com/<pool-id>/.well-known/jwks.json
ORDERHUB_SECURITY_JWT_AUDIENCE=https://api.example.com
ORDERHUB_SECURITY_JWT_TOKEN_PROFILE=COGNITO
ORDERHUB_SECURITY_JWT_ALLOWEDCLIENTIDS=<spa-app-client-id>,<mobile-app-client-id>
```

The token profile is mandatory configuration: omitting it fails startup rather
than silently selecting `GENERIC`. Under `COGNITO` the checks are independent:

- `aud` must equal the configured resource URL of the target Resource Server;
- `client_id` must be a string listed in `allowed-client-ids` for that issuer,
  identifying an admitted Cognito App Client (list each SPA/mobile client that
  may call the API; an empty list fails startup);
- `token_use` must be exactly `access`.

None of these claims is Tenant, Staff, Customer or Platform authority. A token
from the same User Pool but another App Client is rejected. Scopes are not
business permissions.

The Cognito profile requires `token_use=access`; all profiles require expiry,
exact issuer/audience and framework signature/time validation. Provider migration
can configure each additional issuer's profile and its own
`additional-issuers[n].allowed-client-ids` separately. Generic provider mode
exists for other explicitly trusted issuers and the synthetic local issuer; it
is not a recommendation to disable the production Cognito policy. No
`client_id`-for-`aud` fallback exists. Optional `nbf` is not required merely to
accept normal Cognito tokens.

Public clients cannot keep a secret. Do not embed an app-client secret, AWS
credentials or signing keys in a frontend. Keep tokens out of URLs, logs,
analytics and copied error reports. Prefer short-lived in-memory access-token
custody; persistent browser storage increases exposure to injected script. Use
your OIDC library's supported refresh lifecycle and bounded retry behavior.
A BFF can hold tokens server-side for applications that choose that custody or
SSR model; its cookie/session/CSRF protections belong to that separate client.
It is optional for API consumption.

Cognito logout/session termination and local credential removal do not make an
already issued JWT instantly unverifiable at this API. Signature/expiry and
current binding govern subsequent requests; binding revocation takes effect at
new identity resolution. Already authorized in-flight work can finish. Align
short token lifetimes, refresh/logout policy and local app state; do not promise
per-request Cognito revocation introspection that the API does not perform.

## Browser origin admission

Configure exact frontend origins with
`orderhub.security.cors.allowed-origins` (for example an indexed YAML list or
`ORDERHUB_SECURITY_CORS_ALLOWED_ORIGINS=https://shop.example.com`). The default
list is empty. HTTPS origins identify scheme, host and optional port with no
path/trailing slash, userinfo, query or fragment. Omit default ports (`:443` for
HTTPS, `:80` for HTTP), as browsers omit them from Origin; explicit default ports
are rejected at startup. HTTP is only for explicit
loopback development origins. Wildcards, opaque `null` and patterns are rejected.

CORS runs before bearer authentication on business and proof-bootstrap routes.
It admits GET/HEAD/POST/PUT/DELETE and the headers Authorization, Content-Type,
X-Tenant-Id, Idempotency-Key and Accept. It exposes Location, WWW-Authenticate and Retry-After;
preflight can be cached for 600 seconds. It does not admit cookie credentials,
make an endpoint public, confer Tenant membership or expose management/docs.
Unknown origins/methods/headers fail CORS. Requests without Origin retain normal
server-to-server bearer behavior. Browser-readable allowed-origin 401/403 still
mean authentication/authorization failed.

```javascript
// accessToken comes from the public client's Cognito/OIDC library.
// customerId, variantId and tenantId come from authorized application state.
const key = crypto.randomUUID(); // preserve this value AND body for retries
const body = JSON.stringify({customerId, items: [{variantId, quantity: 2}]});
const response = await fetch(`${apiBase}/orders`, {
  method: 'POST',
  credentials: 'omit',
  headers: {
    Authorization: `Bearer ${accessToken}`,
    'Content-Type': 'application/json',
    'X-Tenant-Id': tenantId,
    'Idempotency-Key': key
  },
  body
});
const result = await response.json();
// Inspect response.status and result.code; never log the token or proof.
```

An exact successful retry returns 201 and the original Order; different content
under that key returns 422. A timeout is an uncertain outcome, so reconcile by
retrying the same key/body within bounded backoff, not by minting another key.
503 indicates identity-store unavailability; honor Retry-After with bounded backoff, preserving write idempotency. 401 permits a bounded refresh/reauthentication decision. 403 is an owner policy
failure; refreshing indefinitely will not create permission. Other conflict,
validation, pagination and Problem Details contracts are in the [API guide](../api/README.md).
Administrative paths often use path selectors instead of X-Tenant-Id; follow
generated OpenAPI rather than sending an invented universal selector.

## Onboarding preconditions and local equivalent

A valid Cognito identity does not automatically create an OrderHub account.
Normal routes require an existing active external identity binding to an
internal User. Platform root grants are explicitly provisioned operational trust;
there is no public superuser or first-admin registration endpoint.

The initial Staff ceremony uses Platform authority to issue an existing API
proof, then an externally verified identity consumes it through
`POST /identity/bootstrap/staff`. The proof creates only its admitted Staff
relationships. External identity link proofs have their own dedicated
`POST /identity/bootstrap/external-links` route. Never use Staff creation merely
to give a Customer or machine an identity.

Customer linking starts with an existing CustomerProfile **and a bound internal
User**. Authorized Staff issues
`POST /administration/tenants/{tenantId}/customers/{customerId}/account-link-proofs`;
the intended bound User consumes the proof at
`POST /tenants/{tenantId}/customer-account-links`. The linked Customer may create
and read only its own Orders and gains no Staff permission. General customer
profile creation/self-registration is not a v1 HTTP capability. Advanced
Workforce department/position/privileged workflows are owner application
interfaces, not a complete public backoffice API. A deployment needing automated
new-customer acquisition needs an explicitly governed provisioning integration;
these limitations must be evaluated before choosing v1 for that use case.

Client-side contracts, error mapping, retry semantics and worked examples are in the
[frontend integration guide](../guides/frontend-integration.md) and the
[application integration guide](../guides/application-integration.md).

The [local launcher](../development/local-runtime.md) owns synthetic root grants,
Users, Customer profile, proof flows and stock. It is a demonstrable local
fixture, not a production bootstrap script. Obtain a synthetic token through the
documented loopback CLI; the local signing endpoint deliberately rejects browser
Origin. Configure the API's explicit loopback frontend origin, then supply that
short-lived synthetic bearer to your development client. Never distribute the
local issuer as production authentication.

## Backend services and future boundaries

A backend may act for a legitimately delegated human under the same User and
owner rules. Independent OAuth2 client-credentials/M2M principals are **POST-v1**:
Cognito can issue machine tokens, but v1 has no independent workload identity,
Tenant authority or service permission lifecycle. There is no API key, shared
secret bypass, internal magic header or automatic scope-to-Staff mapping.
A future design must introduce least-privilege service authority explicitly,
with tenant scope, audit, credential rotation/revocation and bounded retries.

Payment integration is also POST-v1. Keep future provider adapters outside owner
domain logic, authenticate channels, bind amount/currency and Order ownership,
version contracts, deduplicate operation/events, verify signatures and replay
windows for inbound webhooks, and provide finite retry/reconciliation. Never
accept raw card data or arbitrary callback URLs. Current Order creation commits
stock; it does not claim payment settlement, reservation TTL or fulfillment.

Future AI/ML belongs to another repository, application and deployment lifecycle.
Use explicitly authorized, versioned API/event boundaries with tenant isolation,
privacy minimization, pseudonymous identifiers, bounded datasets/rates and
retry/deduplication where needed. Do not grant direct transactional database
access, reuse internal Modulith publications as a public stream or add models,
a feature store, vector database, broker or analytics platform for v1.

## Provider references and qualification limits

The production flow follows AWS documentation for [public app clients](https://docs.aws.amazon.com/cognito/latest/developerguide/user-pool-settings-client-apps.html),
[authorization and resource binding](https://docs.aws.amazon.com/cognito/latest/developerguide/authorization-endpoint.html),
[token exchange](https://docs.aws.amazon.com/cognito/latest/developerguide/token-endpoint.html)
and [access-token claims](https://docs.aws.amazon.com/cognito/latest/developerguide/amazon-cognito-user-pools-using-the-access-token.html).
The repository qualifies signed synthetic protocol fixtures and real HTTP;
a deployment must verify its actual Cognito pool/client/redirect/resource and
origin configuration. Synthetic tests are not a claim that a live AWS account,
public DNS/TLS, browser session or production ingress has been certified.

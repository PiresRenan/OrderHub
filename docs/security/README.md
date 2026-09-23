# Security and identity boundaries

OrderHub validates external bearer identity and derives business authority from
its own durable state. External identity, internal User, Tenant membership,
Staff, Customer ownership, role/permission and Platform/Organization authority
are separate concepts. No JWT claim or caller-supplied Tenant UUID replaces
those checks.

Configuration and operational procedures live in the
[configuration reference](../operations/configuration.md) and
[operations guide](../operations/README.md). The accepted design is recorded in
[ADR-0008](../adr/ADR-0008-authenticated-principal-and-trusted-tenant-context.md),
[ADR-0011](../adr/ADR-0011-identity-personas-and-scoped-authorization-kernel.md),
[ADR-0013](../adr/ADR-0013-customer-account-binding-and-ownership-self-service.md)
and [ADR-0017](../adr/ADR-0017-tenant-identity-provisioning-and-account-lifecycle.md).

## Authentication and Tenant selection

The Resource Server verifies the JWT signature, temporal validity, exact issuer
and required audience using Spring Security/Nimbus. Expiry is mandatory for
every trusted provider. The per-issuer `COGNITO` token profile also requires
`token_use=access`, an HTTPS resource audience and a string `client_id` in that
issuer's explicit `allowed-client-ids`; `GENERIC` retains other explicitly
configured providers and does not require `client_id`. The JOSE `typ` header is
compared case-insensitively: `COGNITO` admits `JWT`, and `GENERIC` admits `JWT`
and the RFC 9068 `at+jwt` access-token type (the expected OpenIddict form). An
absent or blank `typ` keeps Spring Security's historical compatibility and is
accepted by both profiles; it is not a recommended form. Any non-blank `typ`
outside the admitted set is rejected.
`typ` is not a purpose check: ID tokens are excluded by the audience contract,
and `COGNITO` keeps `token_use`. The profile itself must
be configured explicitly for every issuer. `aud` binds the target Resource
Server, `client_id` the admitted App Client and `token_use` the token purpose;
none of them confers Tenant, Staff or Customer authority. See [client integration](../integration/README.md)
for public-client PKCE/resource binding and provider migration. Normal authentication then
resolves the exact `(issuer, subject)` through an active Users-owned binding to
an internal User. Unknown or revoked bindings do not become automatic
registration. The application principal carries internal identity, not the raw
JWT, provider roles, email or claim map.

The issuer, audience and JWK endpoint are mandatory environment inputs. Missing
or blank values fail startup. Optional provider overlap is an explicit indexed
allowlist: every additional issuer has a configured JWK endpoint and shares the
primary audience. An unverified issuer may select only from that fixed map; it
never selects a network destination or establishes authenticated identity.
The application performs no issuer discovery at startup. Provider/JWK access
must therefore be checked separately from database-backed readiness.

Orders, Catalog and Inventory use `X-Tenant-Id` as a requested selector. Security
requires a known internal User, active membership for that User/Tenant pair and
an ACTIVE Tenant before establishing trusted context. Their owner services then
apply Customer ownership or Staff permission/authority rules. Other
administrative routes use path selectors and their own explicit owner policy;
do not invent an `X-Tenant-Id` requirement for every route.

An authenticated malformed/missing required selector is a 400. Absent or
ineligible membership and unavailable Tenant trust are denied without revealing
Tenant existence. Missing/invalid bearer credentials take precedence over those
protected business checks and produce 401. Changing a selector cannot create
membership or cross a Tenant boundary.

Membership suspension or termination affects new trusted-context establishment
after commit. Already authorized in-flight work is not retroactively cancelled.
Recovery from SUSPENDED is explicit; TERMINATED is not silently reactivated.
Historical Staff, Customer, Order and audit records are not deleted when access
is removed.

## Business and administrative authority

| Caller relationship | Authority boundary |
| --- | --- |
| Known User only | Authentication; no implied Tenant membership or business permission. |
| Tenant Staff | Current role/permission decision bounded by Workforce placement and authority envelope. |
| Linked Customer | Customer-owned self-service actions for the proved account relationship; no Staff authority. |
| Platform administrative grant | Its explicit Platform action, such as `PLATFORM_TENANTS_MANAGE`; no implicit access to Tenant-private Catalog, Inventory or Customer Orders. |
| Organization administrative grant | Its explicit Organization scope and action; no automatic Tenant business authority. |

There is no `PLATFORM_ROOT` type or universal superuser bypass. Initial Staff
provisioning is a specific Platform ceremony, not a way to treat Platform
identity as ordinary Staff. It requires `PLATFORM_TENANTS_MANAGE`, an ACTIVE
Tenant and serialized proof that no historical Staff/completed ceremony exists.
It establishes the fixed initial governance placement and role ceiling; later
Staff work follows normal Tenant authority and delegation rules.

Authorization precedes sensitive target resolution when an earlier lookup could
become an enumeration oracle. Technical uncertainty fails closed but retains
its technical failure contract rather than being represented as policy denial.
Names and statuses in HTTP documentation describe the implemented operation,
not a general permission to read another module's persistence.

## One-time provisioning and linking proofs

The only routes accepting a verified external identity before internal binding
are `POST /identity/bootstrap/staff` and
`POST /identity/bootstrap/external-links`. They use the same configured JWT
verification as ordinary authentication, then require a separately issued
business proof. A valid token alone creates no User or privileged relationship.
The proof chooses the relationship; request-body identity/role fields cannot
select additional authority.

| Proof | Default lifetime | Issuance and consumption |
| --- | --- | --- |
| Staff provisioning | Configured `30m` default | Privileged issuance freezes the intended Tenant/placement/optional role. Consumption revalidates authority and joins User, binding, membership, Staff and role/evidence effects in one transaction. |
| Customer account link | Fixed `15m`, PostgreSQL time | Current authorized Staff issues for an existing Customer. An already authenticated internal User consumes it; knowledge of Customer UUID alone is not proof. |
| External identity link | Fixed `15m`, PostgreSQL time | An authenticated User issues a proof. An independently verified external identity consumes it and links to that same User. |

Proofs use 256 bits of cryptographic entropy. Persistence retains a SHA-256
digest, not the raw credential. New issuance returns the raw credential once;
matching replay returns only the existing proof/intent identifier, never another
credential. If that successful response is lost, use the operation's authorized
cancellation/reissuance flow with a new operation identity. Do not retry a
different intent under an existing operation identifier.

Expiry, single consumption, competing mutations and downstream failure are
arbitrated by PostgreSQL. Required owner evidence shares the authoritative
transaction; a downstream failure must not leave a spent proof plus partially
created account authority. Secret-bearing DTO text representations are redacted
and lifecycle responses disable caching. Deliver proofs only through the
environment's approved channel; no email/SMS delivery system is implemented.

External identity unlink preserves the original owner and exact provider pair
as inactive; it does not free that identity for reassignment. Migration follows
add-then-remove. Last-path protection requires another active binding whose
issuer remains in the server trust set before removing the current one. This
does not prove that the other provider account is available upstream. No
privileged orphan-account recovery or external IdP administration API exists.

## HTTP, browser and documentation exposure

Business requests use an explicit `Authorization: Bearer` header. The API is
stateless and does not implement form login, HTTP Basic login, an application
login session, refresh-token storage or cookie authentication. CSRF is disabled
for that bearer-only model. A future cookie-authenticated interface requires a
separate CSRF decision; this configuration is not permission to inherit it.

Business API CORS is opt-in through `orderhub.security.cors.allowed-origins`.
The default empty list admits no cross-origin browser client. Exact configured
HTTPS origins (HTTP only for loopback development), bounded methods/headers and
no cookie credentials permit direct frontend consumption. Preflight precedes
authentication on both business and proof-bootstrap chains; it never grants
User or Tenant authority. See the integration guide for examples and error
semantics. Documentation and health exposure do not inherit this allowlist.

Only `/livez`, `/readyz` and `/actuator/health` are public operational endpoints.
Health details are hidden, and the HTTP Actuator exposure includes only health.
There are no exposed metrics, environment, bean, logger, shutdown or event-replay
management APIs.

Generated OpenAPI and Swagger UI are disabled by default, including ordinary
staging/production configurations. The `dev` profile enables them. Anonymous
documentation additionally requires that none of `prod`, `production`,
`staging` or `pre-release` is active. Enabling documentation outside that
condition leaves it behind the normal internal-user bearer boundary. Mixing
profiles can change property enablement, so review both effective configuration
and security policy rather than treating a profile name as isolation.

Swagger authorization persistence is disabled; no preset bearer credential or
remote validator is configured. Supply development credentials explicitly and
keep them out of screenshots, copied examples, reports and stored artifacts.
The generated API uses relative server URLs rather than promoting a
client-controlled host into contract authority.

The [development launcher](../development/local-runtime.md) and its RSA issuer,
seed and token endpoints exist only on the test classpath. The issuer binds to
loopback, admits four fixed synthetic personas, rejects browser Origin and
foreign Host, and requires an explicit empty JSON POST to issue a short-lived
token. Production JAR/container inputs exclude those classes. Setting `dev` on
a production artifact enables its documentation properties, not an account
seed, signing key or token issuer.

## Public errors and sensitive data

Ordinary missing/invalid/unbound bearer credentials receive the same bounded
401 Problem Detail, with `WWW-Authenticate: Bearer` and `Cache-Control: no-store`.
An unavailable identity store instead returns a fixed 503 Problem Detail with
`Retry-After: 1` and no authentication challenge. `Retry-After: 1` is a
deployment/client backoff floor; clients apply bounded exponential backoff. Only
the typed identity-persistence failure is presented as unavailability; an
unexpected identity-resolution defect returns a fixed sanitized 500
(`authentication-failed`) without `Retry-After` or challenge. The filter
boundary discards private causes rather than passing them into servlet error
dispatch.
The two bootstrap paths use their dedicated bounded authentication code. No
decoder exception, raw token, claim value or subject is reflected in those
responses. Business/framework failures retain operation-specific HTTP semantics;
not every failure means 403 or 500.

Strict JSON handling rejects unknown fields, duplicate property names and
unsafe numeric coercion. Bounded Problem Details do not expose SQL, constraint
names, stack traces, rejected private values, proof material or authorization
internals. Anti-enumeration is assessed at each owner boundary; an authorized
caller may receive a resource/state result that an unauthorized caller may not.

Keep bearer credentials, raw one-time proofs, signing private keys, database
passwords and raw idempotency keys out of source, images, logs, metrics and
reports. Exact provider subjects and internal identifiers can be linkable
information even when they are not secrets. The configuration reference marks
database endpoints/usernames and JWT trust topology as environment information;
avoid indiscriminate configuration or exception dumps.

Operational evidence remains owner-local and append-oriented. Analytics consumes
only reviewed Workforce contracts, pseudonymizes subjects and never becomes
authorization truth. Its durable internal notification is not a public webhook
payload. No external destination registry, delivery secret, webhook transport,
broker or generic audit export is implemented. See the
[publication and retention procedures](../operations/README.md#durable-internal-publications).

## Deployment responsibility and verification

The image uses non-root UID/GID `10001`, exec-form Java, and a reduced runtime
tool surface. Compose/Kubernetes enforce an immutable root with explicit
writable temporary storage, dropped Linux capabilities and no privilege
escalation; Kubernetes also disables automatic service-account token mounting
and uses RuntimeDefault seccomp. These controls complement application policy.

The repository does not supply production TLS termination, ingress/network
policy, secret storage/rotation automation, PostgreSQL HA/backups, external
identity-provider administration, or comprehensive detection/alert delivery.
Configure and qualify those environment boundaries before exposing a retained
deployment. No endpoint should be made anonymous merely to ease its smoke test.

Executable security evidence includes real RSA/JWK HTTP tests, PostgreSQL
identity/permission/ownership and concurrency tests, module verification,
production documentation/health posture tests and the disposable developer
business flow. Container/platform checks verify the packaged runtime separately.
Synthetic tests and a green health probe do not establish a production security
or legal compliance certification.

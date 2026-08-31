# ADR-0008 — Authenticated Principal and Trusted Tenant Context

Status: DESIGNED

## Context

OrderHub now owns durable internal User identity and Tenant membership through
the `users` module.

OH-009 established:

- an opaque internal User UUID
- durable TenantMembership
- uniqueness of `(tenantId, userId)`
- same-module referential integrity from TenantMembership to User
- framework-neutral Users application boundaries
- no authentication or authorization mechanism

The HTTP boundary is still intentionally incomplete.

`OrderController` currently accepts `X-Tenant-Id` directly from the caller and
passes that UUID into `CreateOrderCommand`.

The header therefore represents untrusted request context only.

A caller capable of changing `X-Tenant-Id` must not gain access to another
Tenant.

OrderHub also currently has no mechanism for translating an externally
authenticated identity into the stable internal User identity introduced by
OH-009.

Authentication must therefore be established before Tenant context can become
trusted.

## Decision

Introduce a first-class `security` application module responsible for the
application's authentication boundary and derivation of trusted Tenant context.

The authentication flow is:

```text
Bearer access token
        |
        v
Spring Security OAuth2 Resource Server
        |
        v
JWT signature / temporal / issuer / audience validation
        |
        v
validated external identity
(issuer + subject)
        |
        v
users module
external identity resolution
        |
        v
internal User UUID
        |
        v
authenticated internal principal
        |
        v
requested X-Tenant-Id
        |
        v
TenantMembership verification
        |
        v
TrustedTenantContext
        |
        v
Orders HTTP adapter
        |
        v
CreateOrderCommand
```

Neither raw JWT claims nor `X-Tenant-Id` alone establish Tenant authority.

### Authentication Mechanism

OrderHub acts as an OAuth 2.0 Resource Server.

It does not issue access tokens.

JWT bearer access tokens are validated using Spring Security Resource Server
support.

OrderHub must not implement its own:

- JWT parser
- signature verifier
- JWK retrieval mechanism
- `exp` validation
- `nbf` validation
- issuer validation
- audience validation

The security infrastructure uses the framework-supported JWT decoder and
validators.

Production JWT configuration must be externalized.

At minimum, production configuration requires:

- trusted issuer
- expected audience

No development issuer, signing key, JWK endpoint or audience is provided as a
production fallback.

Missing required security configuration must fail closed rather than silently
accepting weaker authentication.

OH-010 may initially operate with one configured trusted issuer at runtime.

The persistence model still uses `(issuer, subject)` rather than `subject`
alone so internal identity does not become globally dependent on one provider
and future multiple-issuer support does not require redefining the binding
identity.

### JWT Validation

A JWT is not considered authenticated solely because its signature is valid.

The accepted JWT must also satisfy the configured validation contract,
including:

- cryptographic signature validity
- token temporal validity
- expected issuer
- expected audience

Issuer and audience validation are explicit security requirements.

A token issued by a cryptographically trusted key for a different audience must
not authenticate to OrderHub.

Tests use synthetic cryptographic material and do not depend on a live external
identity provider.

### External Identity

External authentication identity is represented by the pair:

```text
(issuer, subject)
```

The pair is treated as opaque provider identity.

OrderHub must not derive internal identity from:

- email
- username
- display name
- arbitrary JWT claims
- Tenant claims
- roles supplied by the token

The validated `(issuer, subject)` pair is resolved to an internal OrderHub User
UUID.

A validated token must provide a structurally usable `subject`.

A missing, null or empty `subject` cannot establish an OrderHub identity and
must fail authentication before any external-identity persistence lookup is
attempted.

The configured issuer validation establishes the trusted issuer. The subject is
taken only from the validated JWT `sub` claim; no fallback claim is used.

External identity values are not embedded into the existing User aggregate.

The internal User identity remains authentication-provider neutral.

### External Identity Ownership

The `users` module owns the durable relationship between an external identity
and an internal User.

A new concept, `ExternalIdentityBinding`, represents:

- issuer
- subject
- userId

This concept remains separate from the minimal User aggregate.

The durable uniqueness invariant is:

```text
(issuer, subject) -> at most one internal User
```

One internal User may eventually have multiple external identities.

OH-010 therefore does not impose global uniqueness on `userId` in the binding
table.

No provider-specific User model is introduced.

### External Identity Semantics

`issuer` and `subject` are identity values, not display values.

They must not be:

- lower-cased
- trimmed into a different identifier
- reformatted
- normalized using provider-specific assumptions

Validation may reject structurally unusable values, but a valid identity value
is preserved exactly for deterministic matching.

External identity values are potentially personal or linkable information.

They must not appear in normal:

- logs
- HTTP error bodies
- exception messages
- metrics labels
- tracing attributes

This data minimization does not by itself constitute or prove LGPD compliance.

### External Identity Persistence

External identity bindings are persisted inside the existing PostgreSQL
`users` schema.

Schema evolution uses Flyway V5.

Accepted migrations remain immutable:

- **V1:** Orders
- **V2:** Tenants
- **V3:** initial Users schema
- **V4:** TenantMembership -> User referential integrity

V5 introduces the external identity binding persistence required by OH-010.

The binding references `users.users(id)` through a same-module database foreign
key.

No database foreign key is introduced from `users` to `orders` or `tenants`.

No `ON DELETE CASCADE` behavior is introduced until User deletion semantics are
explicitly designed.

### Users Application Boundary

External identity binding and resolution are exposed through framework-neutral
Users application input ports.

The `security` module must not query:

- `users.users`
- `users.external_identity_bindings`
- `users.tenant_memberships`

directly.

It consumes Users application contracts.

Likewise, the `security` module must not import Users PostgreSQL adapters.

The existing Users application input boundary becomes an explicitly consumable
module API where required by Spring Modulith.

Only application contracts are exposed; persistence and domain internals remain
encapsulated.

### Authenticated Internal Principal

After JWT validation and external-identity resolution, the Spring Security
context represents the caller using an internal principal.

The principal contains only the internal User identity required downstream.

Conceptually:

```text
AuthenticatedUserPrincipal
    userId
```

Raw JWT claims do not become the application principal.

The authenticated principal does not expose:

- bearer token
- issuer
- subject
- JWT claim map
- email
- provider roles
- access-token credentials

The validated JWT exists only as authentication input and is not propagated
into Orders domain/application code.

### Unknown External Identity

A cryptographically valid JWT does not automatically represent a known
OrderHub User.

If `(issuer, subject)` has no binding to an internal User, authentication fails.

The HTTP result is `401 Unauthorized`.

Authentication failures are translated through a controlled HTTP security
failure boundary.

Framework decoder or authentication exception descriptions must not be returned
verbatim to clients.

The public response may preserve standards-required HTTP authentication
semantics, including the appropriate status and `WWW-Authenticate` behavior,
but must not expose token-validation internals, claim values or identity data.

The response must not distinguish, in any way useful for identity enumeration,
among an unknown external subject, a nonexistent internal User, or other
authentication credential failures.

Normal errors must not echo the external subject or internal User UUID.

The response must not distinguish, in any way useful for identity enumeration,
among an unknown external subject, a nonexistent internal User, or other
authentication credential failures.

### Tenant Selector

`X-Tenant-Id` may remain in the public HTTP contract during OH-010, but its
meaning changes.

It is a requested Tenant selector.

It is not:

- proof of Tenant membership
- authentication
- authorization by itself
- an identity claim

The caller may request a Tenant by supplying the header, but OrderHub accepts
that Tenant as trusted context only after membership verification.

For an already authenticated request, a missing or structurally invalid Tenant
selector results in `400 Bad Request`.

Authentication is evaluated before Tenant-context resolution on protected
Orders endpoints. An unauthenticated caller therefore receives `401
Unauthorized` even when the Tenant selector is also missing or malformed.

### Trusted Tenant Context

Introduce a framework-neutral `TrustedTenantContext`.

For OH-010 its minimum state is:

```text
tenantId
```

The authenticated User UUID is used while proving Tenant membership but is not
propagated to Orders unless a concrete downstream requirement later requires
it.

This minimizes security-context propagation.

A `TrustedTenantContext` can only be produced after:

- successful bearer-token authentication
- resolution to an internal User
- parsing of the requested Tenant selector
- successful TenantMembership lookup for that User and Tenant

### Tenant Membership as Access Policy

For OH-010, Tenant membership is sufficient to answer one narrow authorization
question:

> May this authenticated User operate inside this Tenant context?

This does not transform TenantMembership into a generalized authorization
model.

OH-010 introduces no:

- role
- permission
- scope-based business authorization
- RBAC
- ABAC
- ownership policy

Future business operations may require stronger authorization than membership.

Those requirements will be modeled separately.

### Tenant Access Failure

An authenticated internal User requesting a Tenant for which no membership
exists receives:

```text
403 Forbidden
```

The response is stable and privacy-safe.

It must not disclose:

- whether the Tenant exists
- Tenant membership counts
- User UUID
- Tenant UUID
- SQL
- database constraints
- persistence implementation details

Changing only `X-Tenant-Id` must never allow a User to cross Tenant boundaries.

### HTTP Integration

The Orders HTTP adapter must stop treating the raw `X-Tenant-Id` header as
trusted authority.

`OrderController` receives trusted Tenant context only after the security
boundary has resolved it.

The intended controller boundary becomes conceptually:

```text
TrustedTenantContext
        +
CreateOrderRequest
        |
        v
CreateOrderCommand
```

The Orders domain and application layers remain unaware of:

- Spring Security
- JWT
- bearer tokens
- issuer
- subject
- HTTP authentication
- `SecurityContextHolder`

`CreateOrderCommand` may continue to contain `tenantId`, because Tenant identity
is a valid Orders business partition key.

The change is the provenance of that value:

```text
before OH-010:
caller header -> tenantId

after OH-010:
caller selector
    -> authenticated User
    -> membership verification
    -> trusted tenantId
```

### Web Adapter Strategy

Trusted Tenant context is resolved at the HTTP/security adapter boundary before
the Orders application use case executes.

The implementation must avoid a custom application `ThreadLocal`.

Spring Security owns authentication context lifecycle.

A Spring MVC argument-resolution boundary may adapt authenticated principal plus
the requested Tenant selector into `TrustedTenantContext`.

This keeps raw security-framework types out of Orders application/domain code
and avoids making every Orders controller manually inspect the
`SecurityContextHolder`.

The final implementation must preserve an acyclic Spring Modulith dependency
graph.

### Module Dependencies

The intended direction is:

```text
orders
   |
   v
security
   |
   v
users
```

Only adapter/API contracts participate in these dependencies.

The forbidden directions include:

- `users -> security`
- `users -> orders`
- `security -> Orders persistence`
- `orders -> Users persistence`

No dependency cycle is permitted.

Spring Modulith verification must prove the resulting boundaries.

Where a cross-module application port must be consumed, it is exposed
explicitly as module API rather than relying on access to an internal package.

### Security Session Model

The API remains stateless.

Bearer JWT authentication does not create an application login session.

OH-010 introduces no:

- HTTP login session
- form login
- HTTP Basic login
- remember-me authentication
- refresh-token storage

Security configuration uses stateless request semantics.

### CSRF

OrderHub's protected API uses bearer credentials supplied explicitly through the
`Authorization` header and does not introduce browser cookie authentication in
OH-010.

The bearer-token API therefore must not depend on CSRF tokens for
`POST /orders`.

Any CSRF configuration change remains restricted to the stateless API model and
must not be interpreted as a general rule for future cookie-authenticated
interfaces.

### Operational Endpoints

Kubernetes health/readiness probes must remain functional without interactive
user authentication.

Security configuration permits only the operational health paths required by
the deployment platform.

This does not make arbitrary Actuator endpoints public.

Protected business endpoints remain authenticated.

The existing platform health contract remains publicly reachable without
interactive user authentication:

- `/livez`
- `/readyz`
- `/actuator/health`

Only these currently required operational health paths are permitted by
OH-010.

OH-010 must not use a broad rule such as permitting `/actuator/**`.

Other business and management endpoints remain protected according to their
explicit security policy.

### Failure Semantics

OH-010 establishes the following public behavior:

| Condition | HTTP result |
| --- | --- |
| Missing bearer token on protected Orders endpoint | `401 Unauthorized` |
| Invalid signature | `401 Unauthorized` |
| Expired/not-yet-valid token | `401 Unauthorized` |
| Invalid issuer | `401 Unauthorized` |
| Invalid audience | `401 Unauthorized` |
| Valid JWT but missing/invalid subject | `401 Unauthorized` |
| Valid JWT but unknown external identity | `401 Unauthorized` |
| Authenticated User without requested membership | `403 Forbidden` |
| Authenticated User with requested membership | Orders flow continues |
| Authenticated request with missing/malformed Tenant selector | `400 Bad Request` |

Authentication responses remain non-enumerating and privacy-safe.

Authorization responses do not reveal Tenant existence.

### Logging and Observability

Normal security logs must not contain:

- raw `Authorization` headers
- bearer tokens
- complete JWTs
- JWT claim maps
- external subjects
- external identity bindings
- unnecessary User UUIDs
- unnecessary Tenant UUIDs
- SQL
- database credentials

Metrics must avoid high-cardinality labels derived from identity values.

Infrastructure causes may be retained internally by exceptions where useful,
but stable public errors must not expose those details.

## Testing Strategy

Tests must prove the security boundary rather than merely mock its desired
result.

Unit tests cover:

- external identity invariants
- external identity binding orchestration
- external identity resolution
- trusted Tenant-context application logic
- failure semantics

Real PostgreSQL Testcontainers tests cover:

- V5 reconstruction
- binding uniqueness
- binding -> User referential integrity
- persistence/reconstruction
- sanitized persistence failures

Security integration tests use synthetic cryptographic identities.

They must not require:

- internet access
- a live OIDC provider
- production keys
- developer credentials

End-to-end HTTP tests cover at least:

- missing bearer token
- malformed bearer token
- invalid signature
- expired token
- invalid issuer
- invalid audience
- valid token with no internal binding
- valid mapped User without requested Tenant membership
- valid mapped User with requested Tenant membership
- changing only `X-Tenant-Id` cannot cross Tenant boundaries
- successful authenticated Order creation
- operational health/readiness behavior

Tests that bypass cryptographic token validation are insufficient as the sole
proof of the Resource Server boundary.

## Alternatives Rejected

### Trust `X-Tenant-Id` after adding authentication

Rejected.

Authentication proves who the caller is.

It does not prove that the caller belongs to an arbitrary Tenant selected in a
header.

### Put Tenant ID inside the JWT and trust it directly

Rejected for OH-010.

Doing so would couple Orders Tenant authority to identity-provider claim design
and could make membership changes depend on token lifetime.

The durable OrderHub membership remains the Tenant-access source of truth for
this slice.

### Use email as the authenticated User key

Rejected.

It would reverse the authentication-neutral internal identity decision from
ADR-0007 and introduce personal/contact data as core identity.

### Store issuer and subject directly on User

Rejected.

One User may eventually have multiple external identities, and authentication
provider identity is a separate lifecycle concern.

### Query Users tables directly from `security`

Rejected.

It would bypass the Users application boundary and create persistence coupling
between modules.

### Implement JWT parsing manually

Rejected.

Signature, JWK, algorithm and token validation are security-sensitive framework
responsibilities already provided by Spring Security Resource Server.

### Introduce roles now

Rejected.

OH-010 needs only the concrete Tenant-membership access decision.

No current requirement justifies a generalized role/permission model.

### Introduce an OrderHub authorization server

Rejected.

OrderHub is a Resource Server in this phase.

Token issuance, credential verification and authorization-server lifecycle are
separate responsibilities.

### Introduce a custom application `ThreadLocal` tenant holder

Rejected.

It duplicates request-context lifecycle management and creates cleanup/leak
risk.

Trusted Tenant context is adapted at the web/security boundary instead.

## Consequences

### Positive

- callers have a cryptographically validated external identity
- application code operates with stable internal User identity
- external identity providers remain decoupled from the User aggregate
- changing `X-Tenant-Id` cannot bypass Tenant membership
- Orders receives Tenant identity with trusted provenance
- authentication details stay outside Orders domain/application
- durable membership remains the Tenant-access source of truth
- security remains compatible with future multiple external identities

### Trade-offs

- authenticated requests require an external-identity lookup
- Tenant-scoped requests require membership resolution
- external identity provisioning still needs an explicit operational workflow in
  a later slice if no administration surface exists
- availability of the identity provider's JWK infrastructure may affect token
  verification depending on decoder/JWK caching state
- Tenant membership alone remains intentionally less expressive than a future
  role/permission model
- Tenant membership is evaluated at the request authorization boundary and is
  not transactionally coupled to the subsequent Orders write; if membership
  revocation is introduced later, an operation already authorized and in
  flight may complete unless stronger revocation semantics are explicitly
  designed

These trade-offs are accepted for the concrete security requirements of
OH-010.

## Verification

ADR-0008 becomes TESTED only after evidence proves all acceptance criteria below.

### Local verification evidence — 2026-08-31

The implementation has passed the complete local acceptance suite for OH-010:

- [x] Spring Security Resource Server protects Orders
- [x] JWT signature and temporal validation are active
- [x] issuer validation is active
- [x] audience validation is active
- [x] production security configuration fails closed when required JWT trust
  configuration is absent
- [x] issuer, audience and JWK Set URI are supplied explicitly by the deployment
  environment rather than by insecure application defaults
- [x] validated external identity resolves through the Users application boundary
- [x] unknown external identity is rejected without enumeration leakage
- [x] authenticated principal exposes only internal User identity rather than raw
  JWT claims
- [x] V5 creates durable external identity binding from an empty real PostgreSQL
  database
- [x] V1 through V4 remain unchanged
- [x] external identity binding cannot reference a nonexistent internal User
- [x] `(issuer, subject)` cannot resolve to multiple Users
- [x] valid User/Tenant membership produces trusted Tenant context
- [x] absent membership returns a privacy-safe denial
- [x] changing only the Tenant selector cannot cross Tenant boundaries
- [x] Orders application/domain imports no Spring Security/JWT/OAuth2 types
- [x] no cross-module database foreign key is introduced
- [x] module dependencies remain acyclic and Spring Modulith verification passes
- [x] health/readiness probes remain usable without authentication
- [x] existing Orders, Tenants and Users behavior remains green
- [x] synthetic security tests require no live identity provider
- [x] real JWT HTTP tests exercise cryptographic token validation
- [x] real JWT-to-Orders end-to-end tests exercise the complete authentication and
  trusted-Tenant path
- [x] Docker Compose boots with explicit synthetic JWT trust configuration while
  preserving fail-closed behavior when that configuration is absent
- [x] Kubernetes development profile runs two healthy replicas with the external
  JWT trust contract, non-root execution, read-only root filesystem, writable
  `/tmp`, no service-account token, two Service endpoints and the expected PDB
- [x] Kubernetes scale profile runs two healthy replicas on two distinct labelled
  worker nodes, excludes the control-plane and exposes two Service endpoints
- [x] `git diff --check` passes
- [x] `mvnw clean verify` passes with 298 tests, zero failures, zero errors and
  zero skipped tests
- [x] local reproduction of the repository `branch-policy`, `ci-build` and
  `platform-validation` workflows passes
- [ ] repository-required CI checks have passed on the remote GitHub branch/PR

The final unchecked item intentionally keeps this ADR in `DESIGNED` status.

Local workflow reproduction is strong pre-push evidence, but it is not substituted
for the repository's actual remote required checks. The ADR may be promoted to
`TESTED` after those checks complete successfully.

## Follow-up

After OH-010, future security work may introduce explicit authorization
capabilities when concrete business operations require more than Tenant
membership.

Possible future concerns include:

- roles and permissions
- tenant administration
- identity provisioning APIs
- identity unlink/relink lifecycle
- token revocation strategy
- authorization audit
- RLS defense in depth

None of those concerns is introduced by this decision.

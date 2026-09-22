# Disposable local development

> **SYNTHETIC / DEVELOPMENT ONLY / NEVER PRODUCTION.** The launcher, issuer and seed live only on the test
> classpath; see [seed data](seed-data.md) for the full dataset, personas and journeys, and the
> [system usage guide](../guides/system-usage.md) for end-to-end walkthroughs.

Run the real application with synthetic identities, a fresh PostgreSQL container
and a loopback JWT issuer:

```powershell
.\mvnw.cmd -B spring-boot:test-run "-Dspring-boot.run.main-class=io.github.piresrenan.orderhub.development.LocalDevelopmentApplication"
```

Java 21 and a running Docker Linux engine are required. Maven Wrapper downloads
its pinned distribution and dependencies; Testcontainers downloads the pinned
PostgreSQL image when absent. Ports 8080 and 9090 must be available. No `.env`,
Compose stack, installed database or external identity provider is needed.

The application binds to `127.0.0.1:8080`. Development API documentation is at
`http://127.0.0.1:8080/swagger-ui/index.html`; generated OpenAPI is at
`http://127.0.0.1:8080/v3/api-docs`. The explicit launcher enables the `dev`
profile. The separate synthetic issuer binds to `127.0.0.1:9090`.

Every launch owns a newly created disposable database. It never reads an existing
database URL or reuses a developer/production database. Ctrl+C closes the
application, issuer and owned Testcontainer. Restarting reconstructs the same
logical scenario (three Organizations, five Tenants, thirteen personas, catalog,
inventory, Customers and four seeded Orders; see [seed data](seed-data.md)).
Organization, Tenant, User, Order, proof and intent UUIDs are allocated anew;
retrieve current selectors from `/fixture`. Catalog, Customer and operation
UUIDs and synthetic subjects are stable. Do not depend on data surviving a restart.

The launcher constructs its own Hikari pool from that container, bypassing
ambient datasource/Hikari/JNDI binding, and pins Flyway connectivity separately.
The seed additionally checks the actual connection URL before any fixture write.

The issuer publishes public keys at `GET /jwks`, selectors at `GET /fixture`,
and short-lived bearer credentials only after an explicit empty JSON request to
`POST /tokens/{persona}`. Credentials expire after five minutes. Only the personas
listed in [seed data](seed-data.md) are admitted; any other name returns 404. There is no arbitrary-subject or role claim input,
no CORS access, and requests carrying a browser `Origin` are rejected. Tokens and
private keys are never printed by the launcher or written to a fixture file.

The launcher pins its owned JWT profile to `GENERIC`, even when the surrounding
environment is configured for production Cognito. For a separate local frontend,
set `ORDERHUB_SECURITY_CORS_ALLOWED_ORIGINS=http://localhost:5173` before launch
(substitute its exact loopback origin). Obtain tokens through the documented CLI;
the signing endpoint still rejects browser Origin. See [client integration](../integration/README.md).

The four original personas below remain; the complete persona table (thirteen
personas, including multi-Tenant, suspended, terminated, no-role and unbound
identities) is in [seed data](seed-data.md).

| Persona | Authoritative relationship | Useful operation |
| --- | --- | --- |
| `platform` | Internal User with explicit Platform grants | Create/manage Tenants and Organizations; no implicit Tenant-private access |
| `staff` | Initial Tenant governance Staff of Tenant alpha established through the first-Staff proof ceremony | Catalog and Inventory administration |
| `customer` | Internal User linked by the Customer owner proof flow in alpha and beta | Create and view its Customer Orders |
| `outsider` | Bound internal User without any Tenant membership | Exercise denied Tenant access |

Fetch a Customer token without printing it, then create and replay an Order:

```powershell
$fixture = Invoke-RestMethod http://127.0.0.1:9090/fixture
$customerCredential = Invoke-RestMethod -Method Post -Uri http://127.0.0.1:9090/tokens/customer -ContentType application/json
$customerHeaders = @{
    Authorization = "Bearer $($customerCredential.access_token)"
    'X-Tenant-Id' = $fixture.tenantId
    'Idempotency-Key' = "local-order-$([guid]::NewGuid())"
}
$orderBody = @{
    customerId = $fixture.customerId
    items = @(@{ variantId = $fixture.variantId; quantity = 2 })
} | ConvertTo-Json -Depth 4
$created = Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8080/orders -Headers $customerHeaders -ContentType application/json -Body $orderBody
$replayed = Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8080/orders -Headers $customerHeaders -ContentType application/json -Body $orderBody
$created.id -eq $replayed.id
Invoke-RestMethod -Uri "http://127.0.0.1:8080/orders/$($created.id)" -Headers $customerHeaders
```

Both create responses are HTTP 201 with the same Order and allocation result.
The replay does not reserve inventory again. Changing the payload under the
same key returns 422. Preserve the key only for retries of that same request.

Inspect Catalog and Inventory with Staff authority:

```powershell
$staffCredential = Invoke-RestMethod -Method Post -Uri http://127.0.0.1:9090/tokens/staff -ContentType application/json
$staffHeaders = @{
    Authorization = "Bearer $($staffCredential.access_token)"
    'X-Tenant-Id' = $fixture.tenantId
}
Invoke-RestMethod -Uri "http://127.0.0.1:8080/catalog/products/$($fixture.productId)" -Headers $staffHeaders
Invoke-RestMethod -Uri "http://127.0.0.1:8080/inventory/positions/$($fixture.variantId)" -Headers $staffHeaders
$receiptBody = @{
    operationId = [guid]::NewGuid().ToString()
    variantId = $fixture.variantId
    quantity = 5
    reason = 'LOCAL_RECEIPT'
} | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri http://127.0.0.1:8080/inventory/receipts -Headers $staffHeaders -ContentType application/json -Body $receiptBody
```

A Customer token used for Inventory administration is denied with 403. A Staff
token does not inherit Customer ownership and cannot create this Customer's
Order. An `outsider` token proves a known identity but still receives 403 for
the fixture Tenant. Missing/invalid bearer credentials receive 401.

The seed uses real Users, Authorization, Organizations, Tenants, Workforce,
Catalog, Inventory, Customer and Order application services. The initial Platform grants are the explicit
test-only trust-root ceremony. The only fixture-only inserts establish the
synthetic CustomerProfile rows because no Customer creation application command
exists; linking then uses the normal issued proof and consumption. No permission
comes from the JWT. Demo data is absent from migrations.

All launcher, issuer and seed classes are under `src/test/java`. They are absent
from the production JAR and the allowlisted Docker build context. A production
configuration flag cannot enable them. `GET /fixture` and `/tokens/*` are not
application routes. This development issuer is synthetic verification tooling;
production still requires externally supplied database and trusted JWT settings.

Validate the development path with:

```powershell
.\mvnw.cmd -B "-Dtest=LocalDevelopmentAcceptanceTest" test
```

The test starts the same launcher on ephemeral loopback ports, uses real RSA/JWK
verification and PostgreSQL, checks Staff stock access and a receipt, creates
and replays a Customer Order, verifies one inventory commitment, and exercises
persona denial and issuer endpoint restrictions. The normal full suite also
checks that the opt-in fixture does not alter ordinary application startup.

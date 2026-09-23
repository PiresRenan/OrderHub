# OrderHub

OrderHub is a B2B multi-tenant order backend. It combines controlled catalog and
inventory administration with Customer-owned order creation, durable retry
protection, scoped Staff authority and account lifecycle operations.

It is a Java 21 / Spring Boot 4.1.1 modular monolith backed by PostgreSQL 18.6.
Spring Modulith verifies module boundaries and persists the one implemented
asynchronous analytics publication. JDBC and explicit application transactions
keep stock commitments, order creation and idempotency outcomes atomic.

The 1.0.0 candidate is undergoing OH-022 qualification. Stable publication
requires the governed main promotion and immutable release tag; a version field
alone is not release approval.

## Start here

| Need | Guide |
| --- | --- |
| Use the system end to end | [System usage guide](docs/guides/system-usage.md) |
| Build a browser or mobile-web client | [Frontend integration guide](docs/guides/frontend-integration.md) |
| Build a desktop, native, CLI, server-side or BFF integration | [Application integration guide](docs/guides/application-integration.md) |
| Run and authenticate locally | [Development guide](docs/development/local-runtime.md) |
| Explore the seeded development dataset | [Seed data](docs/development/seed-data.md) |
| Understand ownership and consistency | [Architecture](docs/architecture/overview.md) |
| Integrate or use Swagger | [API guide](docs/api/README.md) |
| Connect a Cognito SPA/mobile client or evaluate backend integration | [Client integration](docs/integration/README.md) |
| Understand identities, Tenants and permissions | [Security](docs/security/README.md) |
| Supply runtime configuration | [Configuration reference](docs/operations/configuration.md) |
| Operate, diagnose and recover | [Operations runbook](docs/operations/README.md) |
| Install/upgrade a database | [Migrations](docs/operations/migrations.md) |
| Verify a change | [Testing](docs/testing.md) |
| Review design decisions and delivery state | [ADRs](docs/adr/ADR-0019-api-contract-and-production-readiness.md), [roadmap](docs/ROADMAP.md), [OH-021 evidence](docs/oh021-execution-evidence.md) |

## Run the demonstrable development environment

Install a Java 21 JDK and Docker with Linux containers. Maven comes from the
checked-in Wrapper. From the repository root:

```powershell
.\mvnw.cmd -B spring-boot:test-run "-Dspring-boot.run.main-class=io.github.piresrenan.orderhub.development.LocalDevelopmentApplication"
```

On Linux/macOS use `./mvnw` with the same arguments. The explicit test-classpath
launcher owns a disposable PostgreSQL container, synthetic fixtures and an
ephemeral loopback JWT issuer. The API listens at `127.0.0.1:8080`, the fixture
issuer at `127.0.0.1:9090`. Wait for application startup, then open
[Swagger UI](http://127.0.0.1:8080/swagger-ui/index.html). The development guide
contains the token and selector commands and a complete authenticated flow.

Stopping the launcher ends this disposable environment. It does not connect to
an existing production database. Test fixture code and signing keys are absent
from the production JAR and Docker image. The normal application is a Resource
Server: it neither stores passwords nor issues access tokens.

## Business flow and guarantees

1. A configured external issuer authenticates an identity. Normal routes resolve
   its exact issuer/subject binding to an internal User.
2. Tenant business requests supply `X-Tenant-Id`; the server proves active
   membership and Tenant state. A selector is never authorization by itself.
3. Staff administers Catalog and Inventory within current permissions and
   workforce ceilings. A Customer account binding independently permits
   Customer-owned order creation and reads.
4. `POST /orders` requires `Idempotency-Key`. One PostgreSQL transaction checks
   orderability, commits inventory, saves the Order and completes its durable
   retry outcome. A matching retry returns the same result without more stock
   commitment. Reusing the key for different content returns 422.
5. Authoritative audit/evidence stays with its owner transaction. The existing
   Workforce-to-Analytics notification uses durable Modulith publications and
   idempotent, privacy-minimized processing. Restart recovery is at least once.

No process-local lock is relied on for cross-replica business correctness.
Catalog revisions and Inventory expected-state checks protect concurrent
administrative writes. Bounded cursor pages are current-state reads, not a
snapshot export. Prices are exact integer minor units with explicit currency;
quantities never silently accept fractional truncation.

## HTTP contract

There are 61 explicit operations across Orders, Catalog, Inventory,
Platform/Organization administration, identity/Tenant lifecycle and
self-scoped Tenant discovery (60 frozen v1.0.0 operations plus post-v1 OH-023). Existing
paths do not have a `/v1` prefix. OpenAPI is generated from these real handlers
and their metadata; CI checks coverage and critical representations. The JSON
contract is available at `/v3/api-docs` when deliberately enabled.

Runtime docs and Swagger UI are disabled by default. The explicit `dev` profile
enables convenient local access; deliberate exposure elsewhere retains bearer
authentication. Swagger does not persist authorization. Health probes have a
separate policy and disclose only minimal status.

## Production-shaped runtime

The application requires explicit PostgreSQL and JWT trust configuration. See
[`.env.example`](.env.example) for synthetic Compose interpolation and the
[configuration reference](docs/operations/configuration.md) for effective keys,
defaults, bounds and secret handling. An `.env` file is read by Compose, not
automatically by a host JVM. Fake example issuer addresses do not authenticate
real callers; the local launcher supplies its own isolated issuer.

```powershell
docker compose --env-file .env.example config --quiet
docker compose --env-file .env.example up --build -d
curl.exe --fail http://127.0.0.1:8080/readyz
```

Run this separately from the local launcher because both use port 8080. The
Compose path verifies the packaged runtime with explicit deployment settings;
replace the example trust with your issuer for authenticated use. PostgreSQL is
private to the Compose network. The image runs as UID 10001, with a read-only
root filesystem, ephemeral `/tmp`, no Linux capabilities and bounded shutdown.
Kubernetes manifests demonstrate Restricted pods and replica scheduling; they
do not supply a production database, ingress/TLS or release approval.

Flyway preserves accepted migration history. The fresh-install strategy and
upgrade/equivalence proofs are described in the migration guide. Development
fixtures are separate from production schema/system permission data.

## Verify and contribute

```powershell
.\mvnw.cmd -B clean verify
git diff --check
```

Verification includes real PostgreSQL, real JWT boundaries, HTTP, concurrency,
migration history and Spring Modulith. Docker must be running. See
[testing](docs/testing.md) for focused commands and qualification limits.

[CONTRIBUTING](CONTRIBUTING.md) defines documentation, privacy, branch and PR
requirements. Task branches qualify through PRs into `pre-release`; only a
separately qualified release promotes to `main`. Changes are tracked in the
[changelog](CHANGELOG.md).

## Deliberate limits

There is no frontend, password service, generic registration, payment,
fulfillment, warehouse/reservation service, broker or external webhook delivery.
Internal audit notifications are not a public integration event stream. Platform
privilege does not imply unrestricted Tenant business-data access. Analytics is
a purpose-limited projection, not a general data warehouse. Capacity, latency,
backup objectives and production promotion need deployment-specific evidence.

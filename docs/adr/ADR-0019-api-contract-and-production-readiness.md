# ADR-0019 — API contract and production readiness

Status: DESIGNED

Task: OH-021, [Issue #40](https://github.com/PiresRenan/OrderHub/issues/40).

## Context and admitted baseline

Discovery on 2026-09-12 verified remote `pre-release` at
`c741cb0bfbfd3c8ec66e2c96ad4bc636d357390f`, tree
`e8088e44a0ab7f989c70c6b1e6e1ade1b5b75349`. OH-019 and OH-020 are integrated;
Issue #39 is closed. There are 60 explicit business HTTP operations in six
controllers: Orders (2), Catalog (21), Inventory (8), Platform/Organization
administration (13), account/Tenant lifecycle (14), and identity bootstrap (2).
The existing routes have no `/v1` prefix. Here, v1 means the admitted product
contract; this task does not rename routes or advertise nonexistent CRUD APIs.

The integrated repository has no principal README, generated OpenAPI, local
issuer or demonstrable development seed. Existing specialized HTTP documents
are useful but incomplete as onboarding and operational authority. The Compose
example omits mandatory JWT inputs. Three Kubernetes JSON-limit environment
variables do not bind to the currently hard-coded parser properties.

The old local OH-021 branch at `f1f7891eafd47cc4d35a44a58973ae67342fd4db`
branches from `9e87d15e98db88f95f5829f6e8f57a4aadfb4b80`, before final OH-019
and OH-020. It has no OpenAPI/README implementation and its provisional V38
retention migration conflicts with the accepted V38–V44 sequence. It is not an
implementation authority. Work is rebuilt from current `pre-release`; the old
history remains preserved pending qualified integration and archival cleanup.

## Decision

### HTTP contract authority

Spring MVC handlers, their wire DTOs, validation and owner application semantics
remain authoritative. Use pinned `springdoc-openapi-starter-webmvc-ui` 3.1.1
with existing Spring Boot 4.1.1 to derive OpenAPI 3.1. Annotate HTTP adapters and
use narrow composition customization for injected trusted context, common
security/errors and representations that reflection alone cannot infer.

Every admitted operation needs a stable unique operation ID, meaningful tag,
summary, actor/permission semantics, selectors, success status and applicable
failure responses. Tests compare generated operations with actual MVC handler
mappings. Test output may export a deterministic JSON artifact under `target`;
there is no manually maintained parallel OpenAPI specification.

`X-Tenant-Id` is a required selector for Orders/Catalog/Inventory, validated
against authenticated internal identity, active membership and Tenant state.
Other Tenant paths carry their own selector and owner authorization; do not
invent the header there. Bootstrap accepts a verified external identity only
on its two existing proof-consumption paths. All other business paths require
an active internal binding. No JWT role becomes business authority.

Preserve JSON field names, requiredness, enums, dates and UUIDs. Catalog price,
revision and Inventory quantities parsed through `BigDecimal.longValueExact`
are exact bounded integer values, not arbitrary floating-point decimals.
Document minor units plus canonical currency, UUID cursor ordering, finite
list limits, optimistic preconditions and operation-specific replay behavior.
Orders retains the actual 413 item limit, 409 acquisition conflict and 422
changed-fingerprint key reuse. Issuance uses its real issued/replay JSON shapes;
no invented discriminator or replay credential. Credential examples are
synthetic and no credential is persisted by documentation tooling.

### Errors and environment exposure

Preserve native/framework HTTP status semantics and required response headers.
Problem Details carry bounded public fields, never exception/SQL text, rejected
private values or proof material. Authentication failures must be described
according to executable filter-chain evidence; no claim that an empty native
401 has a JSON body. Close only demonstrated sanitization/status defects using
RED/GREEN regressions. Keep anti-enumeration and one-time response `no-store`.

Runtime API docs and Swagger UI default to disabled in the shared application
configuration, including production and staging. Local/dev exposure is an
explicit choice; only development permits unauthenticated documentation access.
If enabled outside development, documentation paths still require the normal
internal-user bearer boundary. Administrative documentation is consequently an
intentional protected exposure. Swagger authorization persistence is false;
no remote validator or preset bearer credential is configured. Use relative
server URLs so a client-controlled host does not become contract authority.
Swagger exposure and the existing minimal health/probe policy are independent.

### Fresh installation and upgrade safety

The actual Flyway version is 12.4.0, managed by Spring Boot. It supports
`B`-prefix baseline migrations in Community. There are 42 accepted versioned
scripts through V44; V19 and V20 do not exist. Preserve every accepted script
byte, version and checksum, including historical data transformations and
system-owned authorization catalogs. Never call `repair` to hide drift.

Admit a generated B44 fresh-install snapshot only after executable proof that:

1. an empty database uses B44 and reaches the same logical schema and required
   system data as V1–V44;
2. a database with existing versioned history ignores B44, validates accepted
   checksums and upgrades normally;
3. tables, columns/types/defaults/nullability, constraints, indexes (including
   partial/expression indexes), functions/triggers, sequences, schemas and
   extension requirements agree;
4. subsequent migrations work on both histories.

This is distinct from the Flyway `baseline` command and `baseline-on-migrate`.
Neither becomes a deployment shortcut. V migrations remain the evolution
authority; the snapshot is a derived checkpoint with a reproducible generation
procedure and equivalence gate. If the proofs fail, retain normal history and
record the concrete limitation; professional appearance never overrides upgrade
correctness. No paid edition, new database or aesthetic schema refactor.

Place the derived snapshot in `db/baseline` and let runtime Flyway scan both
`db/migration` and `db/baseline`. Historical migration tests continue scanning
only `db/migration`; their intermediate-target and individual-history assertions
must not be weakened. The B44 checkpoint remains frozen while V45+ evolves the
schema. PostgreSQL dump/catalog comparison includes the system permission rows.

### Development authentication and fixtures

Supply an explicit synthetic development launcher on the **test classpath**,
using Spring Boot's supported `spring-boot:test-run` flow and the existing
PostgreSQL test infrastructure where appropriate. An ephemeral RSA issuer binds
only to loopback; it exists to exercise the real Resource Server verification
and internal identity/authorization composition. It is not an identity-provider
product or a production authentication mechanism.

Fixtures use stable synthetic identities and valid owner application services.
Where no application command exists for initial trust/reference data, any
narrow fixture-only persistence is explicit, validated and limited to an owned
disposable development database. No production migration carries demo data.
Fixture code and signing keys are absent from the production JAR and Docker
build context; toggling a production property cannot activate a seed or token
issuer. Document repeatability and disposal instead of silently overwriting an
existing operator-managed database. Prove a useful Staff/Catalog/Inventory and
Customer/Order flow, including authenticated failures and idempotent replay.

### Runtime, operations and integration boundaries

Retain PostgreSQL-backed readiness and process-only liveness on the application
connector, hidden health details, the 25-second shutdown phase and 30-second
container grace period. Preserve pinned images, non-root UID, immutable runtime
filesystem and capability restrictions. Correct inert configuration and make
effective defaults, units, bounds, secret classifications and failure behavior
discoverable. Keep framework defaults unless evidence supports a different
choice; provisional existing transaction bounds are not measured SLAs.

Document pools/timeouts, JWT trust and provider overlap, Flyway validation,
logging, low-cardinality metrics, backup/restore and failure recovery. Production
has no body logging, raw JWT/proof/idempotency-key logging, arbitrary metric
labels, debug endpoints or broad management exposure. Keep CORS/CSRF decisions
consistent with a stateless bearer API, not cookie sessions.

Separate internal events, integration events and external delivery. Existing
Modulith JDBC publications belong to their owner transaction, recover at least
once on restart and require idempotent consumers. A durable internal publication
is not a public webhook contract. Document owner-approved future projections,
privacy, event identity/time/version, deduplication, retry and ordering limits.
No external subscriber/delivery requirement exists in Issue #40; generic webhook
delivery, SSRF surface, broker and destination secrets are therefore deferred.

### Documentation and governance

README is the narrative entry point and links to focused architecture, API,
development, security, configuration, testing and operations guides. Reference
existing ADRs rather than copying them. Correct stale current-state claims
without rewriting historical qualification evidence. Commands, links, config
keys and generated contracts require source or executable evidence.

Refine Issue #40 before main implementation. Record RED/GREEN, targeted checks,
broader regression, final fresh Maven Wrapper `clean verify`, Modulith, migration
equivalence/upgrade, development and container smoke, adversarial findings and
exact candidate SHA/tree. Required GitHub checks are `ci-build`, `branch-policy`
and `platform-validation`. Resolve BLOCKER/MAJOR findings, squash through one
principal PR to `pre-release`, verify integrated authority, then complete the
ADR/ROADMAP/evidence and close the issue as completed. No force push, release
promotion, `main` change or v1.0.0 tag; those remain OH-022.

## Alternatives rejected

- Hand-maintained OpenAPI YAML: duplicates runtime contract authority.
- Global documentation security bypass: administrative metadata exposure and
  confusion with business authorization.
- Production seed profile or committed signing key: activation and secret risk.
- New Keycloak/broker/webhook platform without an existing requirement:
  unnecessary infrastructure for a bounded contract/readiness initiative.
- Deleting/renumbering V1–V44 or importing the stale migration tree: destroys
  upgrade correctness and accepted history.
- Declaring production release qualification from OH-021: crosses OH-022 scope.

## Validation status

Design only. Discovery establishes scope, not implementation qualification.
Status changes to TESTED only after executable and adversarial evidence. See
[execution evidence](../oh021-execution-evidence.md).

## Authoritative references

- [springdoc compatibility and configuration](https://springdoc.org/)
- [springdoc 3.1.1 release](https://github.com/springdoc/springdoc-openapi/releases/tag/v3.1.1)
- [OpenAPI 3.1.1](https://spec.openapis.org/oas/v3.1.1.html)
- [Spring MVC error responses](https://docs.spring.io/spring-framework/reference/web/webmvc/mvc-ann-rest-exceptions.html)
- [Spring Boot Maven test-run](https://docs.spring.io/spring-boot/maven-plugin/run.html)
- [Flyway baseline migrations](https://documentation.red-gate.com/flyway/flyway-concepts/migrations/baseline-migrations)

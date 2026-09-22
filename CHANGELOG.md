# Changelog

All notable OrderHub changes are documented in this file.

The project follows Semantic Versioning.

## [Unreleased]

### Release qualification

- OH-022 adds explicit browser-origin admission without a mandatory BFF,
  requires JWT expiry, and supports a per-issuer Cognito access-token profile
  while retaining generic provider migration.
- Continuous real HTTP qualification connects Platform/Organization, first
  Staff, Catalog, Inventory, Customer linking, Orders and Tenant recovery,
  with durable state and authority-boundary assertions.
- Human Cognito/PKCE guidance distinguishes delegated human access from
  POST-v1 machine, payment and AI/ML capabilities.
- A reproducible mixed-workload experiment measures contention, pool saturation
  and recovery. Artifact metadata is aligned to the 1.0.0 candidate; stable
  publication remains subject to recorded qualification gates.
- The Cognito profile admits only App Client IDs on an explicit per-issuer
  allowlist, and the JWT token profile must be configured explicitly instead of
  defaulting to the generic policy.
- Identity failures are classified at their owning boundary: a typed Users
  persistence failure becomes a sanitized 503, an unbound identity stays 401 and
  an unexpected defect is a sanitized 500 without retry advice.
- The generated OpenAPI is byte-reproducible across JVMs.
- The disposable development launcher seeds a multi-tenant catalog: three
  Organizations, five Tenants, thirteen documented personas, Staff, Customers,
  catalog and inventory states, Orders covering every allocation outcome and
  outstanding proofs, published through a deterministic fixture manifest.
- Real-socket end-to-end suites cover all 60 public business operations, with a
  coverage contract that fails when a served operation has no classified test.
- Verified system usage, frontend integration and application integration
  guides, with executable documentation contracts.
- A production-artifact isolation gate rejects development seed classes,
  resources or markers in the packaged JAR.

### Added

- OH-021 generated OpenAPI 3.1 contract for all 60 business operations, opt-in
  development Swagger UI, and executable contract/documentation drift checks.
- Disposable local PostgreSQL/JWT development launcher with synthetic
  personas, owner-mediated seed data, and real HTTP acceptance coverage.
- Frozen Flyway B44 fresh-install baseline with historical checksum,
  schema/permission equivalence, upgrade and future-migration proofs.
- Engineering entry point and API, architecture, security, development,
  configuration, migration and operations guides.
- Two-stage integration and release governance using `pre-release` and `main`.
- Task-based branch naming and hierarchical integration workflow.
- Automated CI verification for Java 21 and Maven.
- Automated branch-source and pull-request-title policies.
- Pull request engineering template.
- Semantic Versioning and immutable release-tag policy.
- Hardened Orders create-order HTTP vertical slice with explicit application ports, RFC 9457 errors and architecture verification.
- Configurable JSON parser and Orders request resource-safety limits.

### Changed

- Development version lifecycle formalized for the pre-1.0 phase.

### Security

- GitHub Actions dependencies are pinned to immutable commit SHAs.
- Production promotion requires validation before reaching `main`.
- Orders rejects duplicate JSON properties, unsafe numeric coercion and
  excessive parser/request resource consumption.
- API error responses avoid reflecting rejected private values and internal
  exception details.

### Fixed

- JSON parser environment aliases now configure the actual Jackson limits.
- Resource Server authentication and access denials return bounded Problem
  Details without reflecting credentials, selectors or internal causes.
- Pull request CI now fetches the Git history required to compare changes
  against the exact pull request base commit during repository hygiene checks.

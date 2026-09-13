# Verification and delivery gates

Use Java 21 and the checked-in Maven Wrapper. Start Docker with Linux containers;
PostgreSQL acceptance is not replaced by H2. Tests own synthetic, disposable
databases and ephemeral signing keys. No developer `.env` or real issuer is
required by the suite.

```powershell
.\mvnw.cmd -B clean verify
git diff --check
```

Linux/macOS equivalent: `./mvnw -B clean verify`. `clean` is required for the
final candidate so stale classes/reports cannot qualify a different source tree.
Compilation, all JUnit tests, Modulith verification and executable JAR packaging
must pass. A test run with failures or skips is not final qualification.

## Focused development commands

```powershell
.\mvnw.cmd -B "-Dtest=OpenApiContractTest,ProductionHttpPostureTest,ParserEnvironmentBindingTest" test
.\mvnw.cmd -B "-Dtest=FlywayBaselineMigrationTest" test
.\mvnw.cmd -B "-Dtest=LocalDevelopmentAcceptanceTest" test
.\mvnw.cmd -B "-Dtest=OrderHubModularityTests,UsersExposedApiArchitectureTest" test
```

| Evidence | Risks covered |
| --- | --- |
| Domain/application unit tests | Lifecycle invariants, authorization envelopes and failure behavior |
| MVC/JSON tests | Validation, unknown/duplicate fields, numeric parsing, resource bounds and Problem Details |
| Real JWT acceptance | Signature/issuer/audience/time, internal binding, trusted Tenant and persona composition |
| PostgreSQL tests | Actual constraints, transactions, rollback, upgrade and locking rather than database mocks |
| Concurrent and recovery tests | Atomic stock/idempotency, proof races, privilege transitions, replica/restart outcomes |
| Modulith/ArchUnit | Dependency boundaries and exposed interfaces |
| OpenAPI contract tests | Actual handler coverage, unique operation IDs, wire types/errors, UI and production defaults |
| Baseline tests | Full schema/system data, accepted byte checksums, old-history upgrades and later migrations |
| Local development acceptance | Real issuer + application + disposable DB, authorized stock, Customer Order/replay and denied personas |
| Platform checks | Compose/image hardening, probes/shutdown, Restricted Kubernetes and replica placement |

Test methods explain Why / Covers / Prevents. Behavioral work uses RED before
minimal GREEN, then targeted regression. Metadata/docs claims require source or
executable proof; a passing route-count test does not prove every schema is
correct. Generated OpenAPI is exported to `target/contracts/openapi.json`.
Migration proof artifacts are under `target/migration-proof` when generated.

After generation, `node scripts/verify-openapi-text.mjs` checks normalized text
constraints in ECMAScript with and without the Unicode flag. It prevents a
Java-only regex test from accepting patterns that reject supplementary Unicode
characters in JavaScript clients. This optional local check requires Node.js;
CI runs it using the hosted runner's Node runtime. Maven itself still requires
only Java and Docker.

## CI and review

The protected `pre-release` ruleset requires `ci-build`, `branch-policy` and
`platform-validation`, with an up-to-date target and resolved review threads.
The Java job runs full Wrapper `clean verify`; platform validation builds the
actual hardened image and exercises Compose plus two Kubernetes topologies.
The platform JWT URL is deliberately synthetic/unreachable: healthy probes do
not prove usable authentication. Real JWT and local business acceptance cover
that separate boundary.

Qualify the exact pushed candidate SHA/tree, request GitHub Codex review, resolve
material findings with regressions, then requalify any new HEAD. A previous
commit's green checks or review is not approval of changed code. Task branches
use the enforced `<type>/OH-<number>-<description>` convention and a governed
squash PR into `pre-release`. [CONTRIBUTING](../CONTRIBUTING.md) and the PR template
define the review record. OH-022 alone handles release/tag/promotion.

Historical OH-020 clean verification ran 1519 tests. Counts and timings change
with the candidate and host; see [OH-021 evidence](oh021-execution-evidence.md)
for actual final results. No capacity/SLA claim follows from a functional test
duration. Repeat a full suite only for a relevant new candidate or unresolved
failure; use focused tests while iterating.

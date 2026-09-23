# Database installation and upgrades

Flyway owns the PostgreSQL schema, including the Spring Modulith publication registry.
Spring Boot 4.1.1 resolves Flyway **12.4.0**. PostgreSQL **18.6** is the pinned development
and test engine. The accepted history contains **42 versioned scripts**, V1 through V44;
V19 and V20 are deliberately absent and must not be reused.

## Two installation paths, one accepted schema

The application discovers both `classpath:db/migration` and `classpath:db/baseline`.

| Database state | What `migrate` does |
| --- | --- |
| Empty, without Flyway history | Runs `B44__orderhub_schema.sql`, then versioned migrations newer than 44. |
| Already managed by Flyway | Ignores B44 and applies only outstanding versioned migrations. |
| Already current | Validates history and performs no schema changes. |
| Nonempty, without Flyway history | Fails closed; it is not automatically adopted or overwritten. |

B44 is a frozen, generated projection of V1–V44. It creates the final schema directly,
including functions, triggers, constraints, indexes, defaults, and the 24 canonical
permission rows. It includes no users, tenants, credentials, sample orders, or other
development fixtures. A fresh installation records one `SQL_BASELINE` history row at
version 44; it does not fabricate 42 historical execution records.

This is Flyway's **baseline migration** feature. It is different from the `baseline`
command, which marks an existing database as having reached a version without executing
its migrations. B44 and V44 may share a version. The feature is available in Community;
no paid license or extra dependency is required. See the official
[baseline migration semantics](https://documentation.red-gate.com/flyway/flyway-concepts/migrations/baseline-migrations),
[feature matrix](https://documentation.red-gate.com/flyway/learn-more-about-flyway/feature-summary),
and [baseline command](https://documentation.red-gate.com/flyway/reference/commands/baseline).

## Immutable history and future changes

Do not edit, delete, rename, or renumber accepted V scripts. Do not change B44 after its
acceptance. `V1-V44.sha256` records the accepted source bytes; `.gitattributes` keeps SQL
line endings at LF across platforms. Tests verify all 42 hashes.

Future schema or canonical-data changes belong in V45 and later. The post-v1 OH-023
candidate adds `V45__index_tenant_memberships_by_user_status.sql`, an additive index for
self-scoped Tenant discovery ([ADR-0021](../adr/ADR-0021-self-scoped-tenant-discovery.md)).
It builds with a plain `CREATE INDEX` inside the Flyway transaction, which blocks writes to
`users.tenant_memberships` for the duration of the build. Both installation paths
then apply those same migrations after reaching version 44. B44 is not regenerated for
each change. Historical migration tests continue to use `classpath:db/migration` alone;
the dedicated baseline tests use both locations. Moving B44 into `db/migration` would
change the meaning of tests that reconstruct an older schema.

Never use `repair`, `baselineOnMigrate`, out-of-order execution, checksum suppression,
or manual history-table edits to conceal drift. B44 is a fresh-install convenience
requested for OH-021; it is not a measured startup-performance guarantee.

## Reproduce and review the snapshot

The generator requires PowerShell and a running Docker engine. From the repository root,
choose an output file that does not exist:

```powershell
.\scripts\generate-baseline.ps1 -OutputPath "$env:TEMP\B44__orderhub_schema.candidate.sql"
git diff --no-index -- src/main/resources/db/baseline/B44__orderhub_schema.sql "$env:TEMP\B44__orderhub_schema.candidate.sql"
```

The first command verifies the accepted file hashes, creates a disposable PostgreSQL
18.6 container with no published ports, mounts the migration directory read-only, and
executes each accepted script in version order with `psql --single-transaction` and
`ON_ERROR_STOP`. It waits for TCP readiness rather than assuming a fixed startup delay.
It never connects to an application database, overwrites an existing output file, or
changes accepted migration files. Its synthetic password belongs only to that disposable
container, which is removed on completion or failure.

The candidate combines `pg_dump --schema-only --no-owner --no-privileges` with a
column-explicit insert dump of `access_control.permissions` alone. Transport `\restrict`
and `\unrestrict` lines and dump header/version/footer metadata are removed. Dump session
directives become `SET LOCAL`, and the generated `set_config` search-path directive uses
transaction-local scope. The companion `.sql.conf` requires transactional execution.
This prevents dump timeouts/security settings from leaking into reused application pool
connections; a regression test borrows both migration-pool connections after execution.
Object definitions, function bodies, and object comments retain their semantics. The
Flyway history table is excluded. There are no sequence/identity generators, custom
types, or extensions introduced by V1–V44; the complete schema comparison would detect
them if this frozen source changed.

Dump files are copied from the container as bytes and processed with LF-only separators.
PostgreSQL emits literal carriage returns inside Unicode whitespace-check strings;
ordinary line-oriented stdout capture would corrupt those constraints. Direct constraint
catalog equality verifies those characters as well as the operators and bounds.
The generator replaces only the nine exact dumped trim-set literals with the original
`U&'\0009\000A...'` Unicode-escape notation. It checks the occurrence count and preserves
every code point, avoiding hidden control characters and trailing whitespace in reviewed
SQL. It removes extra EOF newlines without rewriting other SQL literals or expressions.

The generator uses the immutable V files as input. The test independently replays those
same files using **Flyway**, so successful generation alone is not the acceptance proof.
Review a regenerated candidate; do not replace an accepted B44 with it.

## Executable acceptance proof

```powershell
.\mvnw.cmd -B '-Dtest=FlywayBaselineMigrationTest' test
```

The test uses the pinned PostgreSQL image and the application's Flyway dependency. It
checks:

- Fresh installation executes exactly one B44, validates, and has no work on a second run.
- A complete `pg_dump` schema and column/constraint catalog comparisons are identical
  between V1–V44 replay and B44, including column ordinals, nullability, generated/identity
  attributes, types, defaults, and collations.
- All 24 permission rows match exactly, the publication registry exists, and every other
  application table is empty.
- A V42 database upgrades through V43/V44 with both locations, retaining every preexisting
  history row and checksum, revoked identity ownership, suspended membership, and customer
  binding data; no B44 execution is recorded.
- A synthetic V45 applies once to both fresh and historical installations and produces the
  same final schema. That probe uses temporary copies of the accepted prefix so an actual
  future V45 will not collide with it.
- Accepted migration bytes remain unchanged and B44 remains outside the historical location.
- Dump session settings do not replace configured timeouts, function-body checks, or row
  security on either reused pool connection.

PostgreSQL deparses `BETWEEN` as nested `AND` nodes and flattens those associative nodes
when restoring its own output. The complete dump comparison therefore restores the
historical dump into another disposable database and dumps it again before comparison;
it never strips or rewrites expression parentheses. Direct column catalogs and PostgreSQL's
pretty-printed constraint definitions are also compared between the original V-chain
database and B44. Original, canonical reference, and B44 dumps are retained under
`target/migration-proof/` for inspection.

These tests run during normal `clean verify`. Runtime bootstrap and health acceptance
also exercise the application's dual-location configuration. This evidence qualifies
OH-021's migration behavior; release promotion and production approval remain OH-022 work.

## Upgrade and recovery procedure

1. Record the application artifact and current Flyway version/history. Confirm the database
   is a managed installation and that a restore-tested backup is available.
2. Review outstanding V migrations and their locking/data effects before starting the new
   application version. B44 never compacts or replaces a deployed history.
3. Start the reviewed application artifact with the intended database configuration.
   Startup validates migration history before applying pending migrations. Route traffic
   only after readiness succeeds.
4. If validation or migration fails, retain logs and the original history. Resolve the
   cause before retrying; do not bypass validation or manually mark a migration successful.

The migration path provides forward schema evolution, not automatic downgrade SQL. A
rollback to an older application requires schema compatibility. If restoration is
necessary, restore the reviewed backup and matching artifact through the deployment
environment's recovery procedure. Never run Flyway `clean` against a retained database.

Decision authority: [ADR-0019](../adr/ADR-0019-api-contract-and-production-readiness.md).

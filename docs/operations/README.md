# Operating OrderHub

OrderHub is a stateless application over a shared PostgreSQL database. Its
committed container and Kubernetes definitions provide a reproducible operating
baseline; they do not provide production database hosting, backup automation,
ingress/TLS, autoscaling or a measured availability/capacity guarantee.

Start with the [configuration reference](configuration.md),
[database installation and upgrade procedure](migrations.md) and
[security boundary](../security/README.md). For interactive synthetic business
flows, use the [disposable development launcher](../development/local-runtime.md).

## Execution environments

| Environment | Database / identity | Intended evidence |
| --- | --- | --- |
| Disposable developer launcher | Owned PostgreSQL Testcontainer; ephemeral loopback RSA issuer and owner-service fixtures | Real authenticated Catalog/Inventory and Customer/Order flows. |
| Compose | Private `postgres` service; mandatory external JWT settings | Packaged image, startup, probes, immutable filesystem and graceful shutdown. |
| Kubernetes local/scale | Environment-provided database Secret and JWT ConfigMap; CI supplies a separate disposable database fixture | Admission, two replicas, Service endpoints, rollout and worker placement. |
| Staging/production | Deployment-owned PostgreSQL, credentials, JWT provider and network controls | Must qualify the actual deployment. Repository fixtures do not supply these services. |

Compose publishes application HTTP on `127.0.0.1`, with no PostgreSQL host port.
The PostgreSQL service has no explicitly named retained volume. Do not rely on
container recreation preserving a reusable database: the image's anonymous
volume is not a documented persistence/restore strategy. Ordinary shutdown
does not justify deleting volumes or pruning unrelated Docker resources.

The Kubernetes PostgreSQL fixture is under `infra/kubernetes/ci`, excluded from
the application base/overlays, and uses `emptyDir`. It is disposable validation
infrastructure. The reusable base consumes `orderhub-database` and
`orderhub-security`; a missing required Secret/ConfigMap prevents a working
deployment rather than selecting a fallback.

## Startup, readiness and shutdown

Startup must establish the configured database and validate/apply Flyway
migrations. Missing JWT trust rejects composition. Direct JWK configuration
avoids issuer discovery at startup; successful startup and database readiness
therefore do not prove that token verification can retrieve signing keys.

| Probe | Public path | Meaning |
| --- | --- | --- |
| Liveness | `/livez` | Process availability, independent of PostgreSQL. |
| Readiness | `/readyz` | Application readiness plus required PostgreSQL health. |
| Minimal aggregate health | `/actuator/health` | Status only; no component details. |

For a local application, these are read-only checks:

```powershell
curl.exe --fail --silent --show-error http://127.0.0.1:8080/livez
curl.exe --fail --silent --show-error http://127.0.0.1:8080/readyz
curl.exe --fail --silent --show-error http://127.0.0.1:8080/actuator/health
```

The healthy response is minimal, for example `{"status":"UP"}`. PostgreSQL
loss makes readiness fail while a healthy JVM remains live. Removing an unready
pod from Service traffic prevents new routing to it; it does not cancel an
already executing request. Avoid replacing liveness with a database probe or a
business request.

The [Deployment](../../infra/kubernetes/base/deployment.yaml) uses startup
`/livez` checks every second with failure threshold 30, readiness every 2 seconds
with failure threshold 3, and liveness every 10 seconds with failure threshold
3. Each probe has a 1-second timeout. These are configured intervals, not an
exact end-to-end failover deadline: scheduling and endpoint convergence add
their own timing.

The JVM is PID 1 in the image and receives SIGTERM directly. Spring has a
25-second shutdown-phase budget; Compose and Kubernetes allow 30 seconds
before forced termination. Existing requests get a bounded completion window.
Keep routing/drain behavior and outer grace periods aligned with those values.
An orderly stop is preferable to forced termination, but committed database
state and durable publication recovery must also survive an abrupt stop.

## Rollouts and connection budgets

The base requests two replicas, `maxUnavailable: 0`, `maxSurge: 1`, three
seconds of minimum readiness, a 120-second progress deadline and a three-revision
history. Its PDB allows one voluntary disruption and uses `AlwaysAllow` for
unhealthy-pod eviction. Replicas spread across node hostnames; the scale overlay
restricts them to the two labelled workers. None of these controls guarantees
that an abrupt node failure has no transient traffic loss.

Read the observed rollout state before routing or declaring a replacement ready:

```powershell
kubectl --namespace orderhub-local rollout status deployment/orderhub --timeout=120s
kubectl --namespace orderhub-local get deployment,pods,service,endpointslice,pdb
```

Use the namespace for the environment under investigation. Verify both Ready
replicas and Ready Service endpoints. If a rollout fails, retain the failing
revision/configuration evidence and inspect pod admission, startup and readiness
causes. Reverting an application image is safe only if it remains compatible
with migrations already applied; there is no automatic schema downgrade.

The container requests CPU `100m` and memory `256Mi`, with limits `500m` and
`512Mi`. Its writable `/tmp` is a 64 MiB memory `emptyDir`. These are provisional
safeguards. Account for temporary rollout replicas and terminating instances
when planning PostgreSQL connections. The inherited default Hikari pool has up
to 10 connections per application instance; other clients and migration/admin
connections also consume database capacity.

Pool acquisition, JDBC transactions, PostgreSQL row locks, JWT key retrieval
and HTTP connector waiting are different boundaries. A 5-second Order
transaction setting is not a 5-second total HTTP deadline. Consult the
[verified inherited defaults](configuration.md#logging-and-inherited-infrastructure-defaults)
before tuning. No PgBouncer, load shedding, custom retry/backpressure layer or
capacity benchmark is supplied. Increase replicas or pool sizes only against an
explicit database budget and measured behavior.

## Failure diagnosis and recovery

| Symptom | Verify first | Recovery boundary |
| --- | --- | --- |
| Application cannot start | Required database/JWT configuration, database reachability, Flyway validation | Correct the environment or apply a reviewed forward migration. Preserve migration history; do not `repair` away drift. |
| `/readyz` fails while `/livez` succeeds | PostgreSQL availability and connection/pool pressure | Restore the database path. A healthy JVM restart does not repair a shared database outage. |
| Health succeeds but bearer requests fail | Exact issuer/audience, current binding, JWK endpoint connectivity and key availability | Restore intended trust/provider access. Do not disable authentication or infer provider health from probes. |
| Orders return 409 during same-key contention | Request key reuse and concurrent operation | Preserve the original key/payload and retry according to the operation contract after the competing outcome is known. |
| Orders return 422 for key reuse | Original request fingerprint | Reconcile the existing intent. A changed request is not a retry of the same idempotency identity. |
| Analytical projection fails | Bounded failure metrics, database availability, consumer/source compatibility | Committed operational state remains authoritative; incomplete publication is retained for restart recovery. |
| Housekeeping fails | Effective duration, batch/delay, database availability, clock/cutoff arithmetic | Correct the policy/environment and allow a subsequent bounded invocation. No unbounded cleanup shortcut exists. |

Record timestamps, artifact revision, environment, bounded status/error codes
and deployment state. Avoid copying raw tokens, proof responses, keys, request
bodies or database URLs/passwords into incident notes. A generic authentication
error deliberately does not disclose whether a subject or binding exists.

### Durable internal publications

Workforce mutation, its append-oriented audit evidence and durable publication
intent share the source transaction. Failure to register the publication rolls
that transaction back. Analytics runs after commit in a separate transaction;
its failure cannot reverse the operational mutation.

`spring.modulith.events.republish-outstanding-events-on-restart=true` is the
deployable recovery path. After correcting the failure, a controlled instance
restart republishes outstanding work. Multiple instances may republish the same
notification. Consumers rely on exact-replay idempotency and reject divergent
duplicates. There is no exactly-once transport, commit-order cursor, periodic
replay scheduler or HTTP resubmission endpoint.

Keep the listener identity `analytics-workforce-authority-change-projection`
and serialized notification compatibility intact while publications remain
outstanding. The current payload contains only `tenantId` and `auditEventId`.
Do not manually delete failed/incomplete publication rows to silence symptoms.
Successful publications disappear automatically under completion mode `delete`.
These rows are recovery state, not expired analytical facts or a public webhook
feed. See [ADR-0014](../adr/ADR-0014-privacy-safe-operational-analytics-foundation.md).

### Analytical housekeeping

Housekeeping is disabled by default. The only admitted dataset is
`analytics.workforce_authority_change_facts`; operational audit, identity,
authorization, Orders, Inventory evidence, analytical subject mappings,
idempotency records and Flyway history are outside its deletion authority.

1. Establish the environment's retention policy and record the chosen positive
   duration. The repository supplies no legal/business duration.
2. Set `orderhub.analytics.housekeeping.retention-window` to that policy,
   `enabled=true`, a batch size in 1–1000 and a positive fixed delay. Check the
   same effective policy on every replica before enabling traffic/processing.
3. Observe executions and deleted-row counters through the deployment's approved
   telemetry integration. Each scheduler invocation processes one batch; backlog
   convergence is gradual, not a promise that all expired rows disappear at once.
4. On failures, correct the cause and retain the bounded retry behavior. To stop
   future scheduled work, deploy `enabled=false`; an already executing database
   statement is not retroactively cancelled.

The cutoff is inclusive and based on operational occurrence time. One SQL
statement selects at most the configured oldest eligible rows with
`FOR UPDATE SKIP LOCKED` and deletes that set atomically. Concurrent replicas can
take disjoint work; rollback leaves rows available for retry. Expired event
replays are ignored before creating subject mappings or facts while retention
is enabled. Disablement also disables that replay-expiry policy, so an old
outstanding event may again be eligible for projection. Reducing the retention
window affects the next invocation; increasing it cannot recreate deleted facts.

Database space management remains PostgreSQL operations. A bounded logical
delete does not promise immediate filesystem shrinkage or replace vacuum/storage
planning. [ADR-0018](../adr/ADR-0018-bounded-operational-data-lifecycle.md) owns the
accepted lifecycle and concurrency semantics.

## Logs and metrics

The application has no configured request-body logger, access-log sink or custom
global tracing pipeline. Narrow logger levels suppress expected JDBC/Hikari and
Flyway infrastructure details while preserving health status and relevant
lifecycle logs. They are not a general sanitizer for arbitrary DEBUG output.
Do not enable SQL bind values, bearer headers or payload logging as a routine
diagnostic step.

Micrometer records these application meters:

| Meter | Kind / bounded dimensions | Interpretation |
| --- | --- | --- |
| `orderhub.orders.create.allocation` | Counter; `outcome`: `fully_allocated`, `partially_backordered`, `fully_backordered` | New successful Orders only; exact replay does not count another allocation. |
| `orderhub.orders.create.failure` | Counter; `reason`: `catalog_item_unavailable`, `insufficient_inventory`, `lock_timeout`, `deadlock`, `transient_database`, `technical_failure` | Create-use-case failures; expected idempotency protocol conflicts use their own meter. |
| `orderhub.orders.transaction.duration` | Timer; no application tags | Time inside the application-owned transaction wrapper, including failed execution. |
| `orderhub.orders.idempotency` | Counter; `outcome`: `first_execution`, `replay`, `fingerprint_conflict`, `in_progress_conflict`, `technical_failure` | Idempotency-boundary events; acquisition is not proof the later Order transaction committed. |
| `orderhub.authorization.decisions` | Counter; `decision`, `persona`, `permission`, `reason` from closed enums | Current authorization decisions. Reasons: `ELIGIBLE`, `POLICY_DENIED`, `UNSUPPORTED_PERSONA`, `PERSISTENCE_FAILURE`. |
| `orderhub.analytics.workforce.authority.change.projections` | Counter; `result`: `projected`, `ignored`, `failed` | Listener outcome recorded on transaction completion. `ignored` includes unsupported analytical vocabulary and, when enabled, already-expired replay. |
| `orderhub.analytics.housekeeping.executions` | Counter; fixed `dataset=workforce_authority_change_facts`, `outcome=success/failure` | One bounded scheduled invocation. |
| `orderhub.analytics.housekeeping.rows.deleted` | Counter; same fixed dataset | Rows deleted by the bounded housekeeping operation. |

No Tenant, User, Staff, Customer, Order, Variant, subject, correlation,
idempotency-key or fingerprint value is a meter label. Counters are process
telemetry, not durable audit/accounting records. The application does not expose
`/actuator/metrics` or `/actuator/prometheus`, and it configures no exporter.
Meter existence alone does not establish remote monitoring or alert delivery;
that requires an explicit deployment integration and exposure decision.

## Backup, restore and retained state

The deployment owner must provide PostgreSQL backups, access control, encryption,
retention, recovery objectives and restore testing. No production backup job or
RPO/RTO is implemented by this repository. An image, B44 schema snapshot, schema
dump or disposable fixture is not a backup of business state.

Back up a transactionally consistent database using the deployment's approved
PostgreSQL tooling, including all application schemas, `public.event_publication`
and Flyway history. Preserve database roles/privileges and external configuration
through the environment's corresponding protected procedures. Do not back up
only Orders while omitting its idempotency or Inventory/identity state.

Before relying on a backup, restore it into an isolated target and verify the
matching application artifact, Flyway validation, relevant business invariants,
readiness, authentication, and pending-publication recovery. Keep that exercise
away from active clients and from the original database. Record the observed
restore outcome without extracting sensitive rows into ordinary reports.

A rollback or restore must reconcile the application/schema pair and any work
accepted after the backup point. Restore is not a reason to change accepted
migration checksums, free durable idempotency keys or discard recovery records.
Follow the [migration guide](migrations.md) for immutable history, B44 fresh
installation and forward-only evolution.

Platform design/evidence remains in
[ADR-0004](../adr/ADR-0004-containerized-development-and-runtime-platform.md) and
[ADR-0005](../adr/ADR-0005-postgresql-persistence-and-transaction-boundaries.md).
Their historical experiments describe the tested environment at that checkpoint;
they are not current production availability promises.

# v1 qualification experiments

The executable procedure is in [the probe guide](../../scripts/qualification/README.md).
These observations qualify bounded failure and durable correctness in a local
environment. Deployment capacity and an SLA require measurements on the actual
deployment. Final immutable commit, check runs and artifact identities belong
to the governed release evidence, not an inferred identity from these numbers.

## Measured environment

The September 22, 2026 experiment (after the Cognito client admission and
identity-failure classification corrections, same parameters) used Windows 11 build
26200, Intel i5-11400H (6 cores / 12 logical processors), 34,128,322,560 bytes of
host RAM, Java 21.0.12+7-LTS-205 and Docker 29.8.0. PostgreSQL 18.6 used the
repository's pinned test image. Docker reported 15.62 GiB available; no database
container CPU/memory limit was imposed. One application replica and the
closed-loop HTTP client shared a JVM (maximum heap 8,535,408,640 bytes).

The synthetic dataset has one tenant, customer and hot SKU. Orders and evidence
grow during the run. Hikari maximum is 10, acquisition timeout is 30 seconds,
and normal client request timeout is 15 seconds. Five seconds of warmup precede
two repetitions of 15-second stages at concurrency 1, 4, 16 and 64, followed by
concurrency 1 recovery. Each ten-request cycle includes three reads, receipt and
adjustment with matching replays, and Order creation, replay and an expected
422 fingerprint conflict. Authentication and authorization run for each request.
This mix does not measure every API endpoint or a full business journey.

## All measured stages

Latency values are milliseconds. Counts include draining requests at stage end.
Expected 422 conflicts are not unexpected failures. Every stage had zero
unexpected status/transport failures and no cycle cap was reached.

| Stage | Concurrency | Requests | Requests/s | p50 | p95 | p99 | Max | Peak pool pending |
|---|---:|---:|---:|---:|---:|---:|---:|---:|
| Warmup | 1 | 160 | 31.5 | 29.70 | 48.52 | 84.41 | 149.15 | 0 |
| Repeat 1 | 1 | 670 | 44.6 | 22.26 | 33.46 | 43.04 | 54.70 | 0 |
| Repeat 1 | 4 | 2730 | 180.5 | 21.01 | 37.06 | 51.11 | 108.38 | 1 |
| Repeat 1 | 16 | 3950 | 255.6 | 55.25 | 126.74 | 179.53 | 282.96 | 6 |
| Repeat 1 | 64 | 5130 | 325.0 | 155.72 | 470.59 | 727.04 | 1486.97 | 54 |
| Recovery 1 | 1 | 1180 | 78.3 | 12.81 | 19.89 | 25.85 | 36.40 | 0 |
| Repeat 2 | 1 | 1120 | 74.0 | 13.55 | 20.57 | 26.43 | 35.07 | 0 |
| Repeat 2 | 4 | 3910 | 258.5 | 14.96 | 25.19 | 38.08 | 79.29 | 0 |
| Repeat 2 | 16 | 5400 | 353.8 | 41.17 | 86.36 | 129.10 | 242.12 | 6 |
| Repeat 2 | 64 | 5460 | 344.2 | 152.78 | 420.04 | 606.86 | 1247.36 | 54 |
| Recovery 2 | 1 | 1060 | 70.3 | 14.16 | 22.26 | 29.04 | 40.22 | 0 |

At 64 clients the pool had 54 pending requests, and repeat 2 throughput
stopped increasing while p99 rose substantially. Absolute throughput is lower
than the September 20 pre-commit run on the same host and parameters; host
load differed and no cause is claimed. Those earlier numbers are superseded. This demonstrates saturation
and queueing for this workload. Recovery stages cleared the queue. Warmup/JIT,
shared CPU and a growing dataset prevent treating differences between repeats
as a controlled performance optimization. No throughput optimization was made.

The final database had 3,077 unique Orders, committed quantity 3,077 and on-hand
quantity 1,003,177, exactly matching observed successful effects. Replay
representations had zero mismatches. Final readiness was 200.

## Controlled faults

Holding all ten pool connections produced a sanitized authenticated 503 after
30.028 seconds, within the deliberately longer 45-second fault-client deadline.
Liveness remained 200. Releasing the connections restored readiness and left
stock unchanged. The initial experiment exposed a misleading 401 and private
persistence cause on servlet error dispatch; a failing signed-filter regression
preceded the identity-store failure correction.

Temporarily disabling LOGIN for only the disposable database role and terminating
its other connections produced business/readiness 503 and liveness 200. LOGIN
was restored using the retained recovery connection. Recovery took 0.572 seconds
and stock was unchanged. This is a database-access outage, not a server crash.
An earlier Docker stop/start experiment changed the fixture's ephemeral host
port; that invalid addressing experiment is excluded from recovery conclusions.

## Scope and interpretation

The probe also emits per-operation distributions/status histograms, JVM CPU,
heap/nonheap and GC, sampled pool occupancy, database transaction/deadlock/lock
statistics and owned-container CPU/memory samples. Sampling can miss short lock
waits; container memory is not PostgreSQL RSS, and HTTP latency is not isolated
transaction duration. Unavailable container samples are explicitly marked.

This is neither an open-loop capacity test nor a multi-replica throughput test.
It excludes internet latency, real Cognito/JWK availability, ingress/TLS,
disk/network profiling and large tenant/dataset distributions. Separate
PostgreSQL integration tests exercise structural races, publication restart,
retention, idempotency and durable recovery. The signed-token tests establish
the configured Cognito protocol boundary; they do not certify a live AWS account.

No migration changes are part of this release qualification. Rollback must
follow [the operations runbook](README.md) and the immutable migration policy;
do not erase committed Orders, evidence or idempotency state to recover service.

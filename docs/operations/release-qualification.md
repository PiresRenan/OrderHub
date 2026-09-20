# v1 qualification experiments

The executable procedure is in [the probe guide](../../scripts/qualification/README.md).
These observations qualify bounded failure and durable correctness in a local
environment. Deployment capacity and an SLA require measurements on the actual
deployment. Final immutable commit, check runs and artifact identities belong
to the governed release evidence, not an inferred identity from these numbers.

## Measured environment

The September 20, 2026 corrected pre-commit experiment used Windows 11 build
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
| Warmup | 1 | 450 | 88.8 | 10.19 | 18.48 | 29.53 | 67.99 | 0 |
| Repeat 1 | 1 | 1810 | 120.7 | 8.00 | 12.94 | 16.52 | 26.39 | 0 |
| Repeat 1 | 4 | 5920 | 393.5 | 10.45 | 14.33 | 18.88 | 39.09 | 0 |
| Repeat 1 | 16 | 9810 | 648.1 | 23.41 | 41.11 | 62.46 | 179.12 | 7 |
| Repeat 1 | 64 | 12770 | 832.2 | 71.99 | 134.67 | 185.34 | 332.08 | 55 |
| Recovery 1 | 1 | 2640 | 175.5 | 5.80 | 8.27 | 9.82 | 15.74 | 0 |
| Repeat 2 | 1 | 2800 | 186.0 | 5.60 | 7.58 | 8.92 | 12.45 | 0 |
| Repeat 2 | 4 | 8370 | 555.4 | 7.62 | 9.93 | 11.27 | 19.44 | 0 |
| Repeat 2 | 16 | 14670 | 970.2 | 16.91 | 23.27 | 26.74 | 42.69 | 6 |
| Repeat 2 | 64 | 14630 | 953.1 | 63.68 | 113.68 | 143.64 | 264.12 | 54 |
| Recovery 2 | 1 | 2790 | 185.9 | 5.56 | 8.04 | 9.59 | 13.05 | 0 |

At 64 clients the pool had 54–55 pending requests, and repeat 2 throughput
stopped increasing while p99 rose substantially. This demonstrates saturation
and queueing for this workload. Recovery stages cleared the queue. Warmup/JIT,
shared CPU and a growing dataset prevent treating differences between repeats
as a controlled performance optimization. No throughput optimization was made.

The final database had 7,666 unique Orders, committed quantity 7,666 and on-hand
quantity 1,007,766, exactly matching observed successful effects. Replay
representations had zero mismatches. Final readiness was 200.

## Controlled faults

Holding all ten pool connections produced a sanitized authenticated 503 after
30.025 seconds, within the deliberately longer 45-second fault-client deadline.
Liveness remained 200. Releasing the connections restored readiness and left
stock unchanged. The initial experiment exposed a misleading 401 and private
persistence cause on servlet error dispatch; a failing signed-filter regression
preceded the identity-store failure correction.

Temporarily disabling LOGIN for only the disposable database role and terminating
its other connections produced business/readiness 503 and liveness 200. LOGIN
was restored using the retained recovery connection. Recovery took 0.530 seconds
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

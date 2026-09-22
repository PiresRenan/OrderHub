# v1 qualification experiments

The executable procedure is in [the probe guide](../../scripts/qualification/README.md).
These observations qualify bounded failure and durable correctness in a local
environment. Deployment capacity and an SLA require measurements on the actual
deployment. Final immutable commit, check runs and artifact identities belong
to the governed release evidence, not an inferred identity from these numbers.

## Measured environment

The current qualification evidence is one controlled run on September 22, 2026
against the code of commit `061f2b7` (the probe ran on that exact tree; later
commits that only change this document leave the executable tree unchanged).
The host was Windows 11 build 26200, Intel i5-11400H (6 cores / 12 logical
processors), 34,128,322,560 bytes of host RAM, Java 21.0.12+7-LTS-205 and Docker
29.8.0. PostgreSQL 18.6 used the repository's pinned test image. No database
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

## Host precondition

The probe must run without material concurrent host workload. Two attempts
were blocked before any measurement: the first by a game using about 1.7 cores
continuously (host CPU 62-94%), the second by a browser process using about one
to two cores continuously. After both were fully terminated and a 60-second
settle, three samples one minute apart (samples 7-9) showed host CPU 0-8%,
about 19.6 GB free memory, no browser or game process, no Docker containers and
only an idle Java language server. Precondition: satisfied.

## Controlled run

Parameters `15 2 5` (stage seconds, repeats, warmup seconds), executed exactly
once; exit status 0. Latency values are milliseconds. Counts include draining
requests at stage end. Every stage returned only 201, 200 and the expected 422
fingerprint conflict: zero unexpected statuses, zero transport failures, zero
deadlocks, and no cycle cap was reached.

| Stage | Concurrency | Requests | Requests/s | p50 | p95 | p99 | Max | Peak pool pending | Status 201 / 200 / 422 |
|---|---:|---:|---:|---:|---:|---:|---:|---:|---|
| Warmup | 1 | 530 | 105.5 | 9.13 | 13.51 | 19.83 | 62.80 | 0 | 318 / 159 / 53 |
| Repeat 1 | 1 | 2310 | 153.4 | 6.63 | 8.79 | 10.87 | 17.65 | 0 | 1386 / 693 / 231 |
| Repeat 1 | 4 | 7420 | 492.9 | 8.30 | 11.45 | 14.49 | 37.87 | 0 | 4452 / 2226 / 742 |
| Repeat 1 | 16 | 14970 | 991.6 | 16.20 | 24.04 | 32.37 | 63.25 | 6 | 8982 / 4491 / 1497 |
| Repeat 1 | 64 | 15330 | 999.3 | 61.04 | 108.28 | 136.69 | 274.25 | 54 | 9198 / 4599 / 1533 |
| Recovery 1 | 1 | 3110 | 207.3 | 5.10 | 6.52 | 7.91 | 11.70 | 0 | 1866 / 933 / 311 |
| Repeat 2 | 1 | 3120 | 207.6 | 5.11 | 6.57 | 7.72 | 13.72 | 0 | 1872 / 936 / 312 |
| Repeat 2 | 4 | 9170 | 609.3 | 6.97 | 9.01 | 10.77 | 18.82 | 0 | 5502 / 2751 / 917 |
| Repeat 2 | 16 | 15680 | 1038.5 | 15.57 | 22.20 | 28.72 | 63.93 | 6 | 9408 / 4704 / 1568 |
| Repeat 2 | 64 | 15770 | 1031.2 | 58.85 | 104.94 | 130.54 | 219.27 | 54 | 9462 / 4731 / 1577 |
| Recovery 2 | 1 | 3050 | 203.2 | 5.16 | 6.89 | 8.96 | 13.27 | 0 | 1830 / 915 / 305 |

At 64 clients the pool had 54 pending requests and throughput stopped
increasing relative to 16 clients while p99 rose, demonstrating saturation and
queueing for this workload. Recovery stages cleared the queue. Warmup/JIT,
shared CPU and a growing dataset prevent treating differences between repeats
as a controlled performance optimization. No throughput optimization was made.

The final database had 9,046 unique Orders, committed quantity 9,046 and on-hand
quantity 1,009,146, exactly matching observed successful effects. Replay
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
was restored using the retained recovery connection. Recovery took 0.540 seconds
and stock was unchanged. This is a database-access outage, not a server crash.
An earlier Docker stop/start experiment changed the fixture's ephemeral host
port; that invalid addressing experiment is excluded from recovery conclusions.

## Comparison with historical runs

Historical runs used the same host and parameters and are retained as history,
not as current evidence.

| Stage | Concurrency | Requests/s now / Sep 20 / degraded Sep 22 | p95 now / Sep 20 / degraded | p99 now / Sep 20 / degraded |
|---|---:|---|---|---|
| Repeat 1 | 1 | 153.4 / 120.7 (+27.1%) / 44.6 | 8.79 / 12.94 (-32.1%) / 33.46 | 10.87 / 16.52 (-34.2%) / 43.04 |
| Repeat 1 | 4 | 492.9 / 393.5 (+25.3%) / 180.5 | 11.45 / 14.33 (-20.1%) / 37.06 | 14.49 / 18.88 (-23.3%) / 51.11 |
| Repeat 1 | 16 | 991.6 / 648.1 (+53.0%) / 255.6 | 24.04 / 41.11 (-41.5%) / 126.74 | 32.37 / 62.46 (-48.2%) / 179.53 |
| Repeat 1 | 64 | 999.3 / 832.2 (+20.1%) / 325.0 | 108.28 / 134.67 (-19.6%) / 470.59 | 136.69 / 185.34 (-26.2%) / 727.04 |
| Repeat 2 | 1 | 207.6 / 186.0 (+11.6%) / 74.0 | 6.57 / 7.58 (-13.3%) / 20.57 | 7.72 / 8.92 (-13.5%) / 26.43 |
| Repeat 2 | 4 | 609.3 / 555.4 (+9.7%) / 258.5 | 9.01 / 9.93 (-9.3%) / 25.19 | 10.77 / 11.27 (-4.4%) / 38.08 |
| Repeat 2 | 16 | 1038.5 / 970.2 (+7.0%) / 353.8 | 22.20 / 23.27 (-4.6%) / 86.36 | 28.72 / 26.74 (+7.4%) / 129.10 |
| Repeat 2 | 64 | 1031.2 / 953.1 (+8.2%) / 344.2 | 104.94 / 113.68 (-7.7%) / 420.04 | 130.54 / 143.64 (-9.1%) / 606.86 |

Against the degraded September 22 run, throughput is 136-288% higher and p95
64-81% lower at every concurrency. Repeat 2 at concurrency 16 had an isolated
p99 increase of 7.4% versus the 2026-09-20 run, while throughput increased 7.0%
and p95 decreased 4.6%. No broader latency regression was observed; no
statistical-noise claim is made.

The degraded September 22 run (3,077 unique Orders, exact stock, zero replay
mismatches, pool 503 at 30.028 seconds, database recovery 0.572 seconds) was
taken without a host precondition check, at a time when material concurrent
host workload was later observed. The controlled rerun is consistent with the
earlier degradation having been environmentally influenced. No cause is claimed.
The September 20 run (7,666 unique Orders, exact stock, zero replay mismatches,
pool 503 at 30.025 seconds, database recovery 0.530 seconds) was a pre-commit
measurement of an earlier candidate.

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

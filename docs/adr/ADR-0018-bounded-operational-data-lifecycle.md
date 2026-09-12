# ADR-0018 — Bounded Operational Data Lifecycle

Status: TESTED

## Context

OH-020 inspected all 45 migration-declared tables plus Flyway schema history on
the approved OH-019 checkpoint. Age is not deletion authority. Authoritative
business state, authorization state, inventory evidence, privileged audit and
Flyway history are not purgeable. Orders request idempotency deliberately owns
its keys indefinitely and exposes no retry horizon. Analytical subject mapping
has no time or terminal marker.

Completed Spring Modulith publications were an initial candidate. OrderHub
already configures completion mode `DELETE`, so successful publications are
removed at completion and only recovery-relevant incomplete/failed rows remain.
The public `CompletedEventPublications` cleanup methods are also unbounded; no
registry cleanup is introduced merely to duplicate the existing lifecycle.

Workforce authority-change facts are analytics-owned derivatives with explicit
occurrence time and owner-defined finite policy. ADR-0014 left retention unwired
because a late event-publication replay could recreate a fact after expiry.

## Decision

Analytics admits its workforce authority-change facts as the only OH-020 purge
dataset. The capability is opt-in: enabling it requires a positive retention
window supplied by deployment policy. No legal or business duration is invented.

The same owner policy is applied at ingestion. A replay whose operational
`occurredAt` is already expired is acknowledged as ignored before subject
mapping or fact persistence. It therefore cannot resurrect deleted data.

Each scheduled invocation is only a trigger for one batch. The analytics owner
derives the inclusive cutoff from a supplied UTC clock. One PostgreSQL statement
selects at most the configured 1..1000 oldest eligible rows in deterministic
order, locks them with `FOR UPDATE SKIP LOCKED`, and deletes exactly that set.
The statement is its own atomic transaction scope. Concurrent instances select
disjoint available work; rollback restores the batch for retry; repeated calls
converge. V44 adds the B-tree matching fact type, cutoff and order.

Only fixed `dataset` and bounded `outcome` metric dimensions are emitted. No
HTTP or administrative deletion surface, payload logging, identifier label,
new service, broker, cache, distributed scheduler or JVM lock is introduced.

The existing Tenant-scoped application primitive is retained for compatibility
but is capped at 1000 rows. Production scheduling uses only the global bounded
owner operation and never enumerates Tenants.

## Consequences

Cleanup is disabled safely until a deployment supplies a defensible duration.
Once enabled, storage is drained gradually and multiple instances may cooperate
without correctness depending on a process-local lock. The index has write and
storage cost. An expired late event is intentionally not represented in the
current analytical projection; operational workforce audit remains authoritative.

Final integrated OH-019 Flyway history ends at V43, so V44 is the first free
migration authority for this analytical retention index. Post-OH-019 replay,
migration reconciliation and the canonical local full-suite qualification are
green. Final review corrected Boolean-alias activation so ingestion and cleanup
use the same effective policy. The fresh full suite passed 1519 tests with zero
failures, errors or skips; exact-candidate CI, Platform CI and Branch Policy
passed and material review findings were resolved. PR #45 was squash-integrated
as `d722487320354a7fdc3742b1446eba498f4a7a7f`, with the expected OH-019 parent
and a tree identical to the reviewed candidate. The
[execution evidence](../oh020-execution-evidence.md) records qualification,
review dispositions and integration proofs.

## Alternatives

- Purging Orders idempotency was rejected because it would silently restore an
  old key's authority without a contractual retry horizon.
- Purging analytical subject mappings or privileged evidence was rejected
  because their deletion semantics are unproven or prohibited.
- Retaining a separate tombstone for expired facts was rejected as additional
  durable personal-linkable state; evaluating the owner policy before replay is
  smaller and preserves the same deadline.
- Direct event-publication SQL and switching completion mode to `UPDATE` were
  rejected because successful publications already do not accumulate.
- A generic housekeeping module was rejected because one concrete owner does
  not justify a cross-module framework.

## Validation

Executable evidence must cover inclusive expiry, too-new survival, batch cap,
repeatability, concurrent workers, rollback retry, expired replay suppression,
configuration bounds, index shape, low-cardinality metrics, module verification,
and a fresh Maven Wrapper `clean verify` against PostgreSQL.

## References

- Spring Modulith 2.1 application events and `CompletedEventPublications` API:
  https://docs.spring.io/spring-modulith/reference/events.html
- PostgreSQL 18 bounded `DELETE` CTE pattern:
  https://www.postgresql.org/docs/18/sql-delete.html
- PostgreSQL 18 locking and `SKIP LOCKED`:
  https://www.postgresql.org/docs/18/sql-select.html
- PostgreSQL 18 index ordering:
  https://www.postgresql.org/docs/18/indexes-ordering.html
- PostgreSQL 18 routine vacuuming boundary:
  https://www.postgresql.org/docs/18/routine-vacuuming.html
- ADR-0003, ADR-0010 and ADR-0014.

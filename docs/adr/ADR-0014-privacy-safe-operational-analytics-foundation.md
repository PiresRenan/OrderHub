# ADR-0014 — Privacy-Safe Operational Analytics Foundation

Status: DESIGNED

## Context

OrderHub produces concrete operational evidence — workforce authority changes
under ADR-0012, Customer and Order activity under ADR-0013 — but has nowhere to
derive analytical facts from it.

Operational modules are the authoritative systems of record. `workforce` owns
workforce operational and audit evidence. Analytics needs privacy-reduced facts
derived from that evidence, without duplicating personal data and without
becoming a second source of truth.

Two constraints shape the decision: analytics must never become authorization
truth, and no module may reach into another module's persistence. ADR-0011
already set the governing objective — maximize analytical information density
while minimizing unnecessary personal-data processing.

OH-016 must therefore establish durable analytical boundaries now, while the
transport that moves operational evidence into analytics is not yet supported by
evidence strong enough to choose it.

## Decision

`analytics` is established as a first-class Spring Modulith module, and is
assigned ownership of analytical fact schemas, analytical subject identity, data
classification, retention and derived analytical projections.

Analytics will consume operational facts only through explicit application
contracts. It owns its own PostgreSQL schema, and does not participate in
operational correctness.

The module is declared fail-closed, permitted no dependency on any other
application module, so a future cross-module edge must be an explicit reviewed
change rather than an accidental import.

Customer semantics are present in the integrated baseline. That does not admit
Customer analytical facts into this slice; doing so requires an explicit
governed scope change.

This decision records the architecture. It deliberately does not select an
ingestion mechanism; see "Ingestion decision status".

## Architectural invariants

**Ownership and boundaries.**

- analytics owns analytical facts, analytical subject identity, classification,
  retention and derived projections;
- operational modules remain authoritative for business state;
- analytics-derived information is never operational authorization truth;
- analytics must not query or import another module's persistence model;
- cross-module references must use explicit contracts and opaque identifiers;
- the module dependency graph must remain acyclic and explicitly declared.

**Fact schema identity.**

- every persisted analytical fact must carry a bounded fact type and an explicit
  positive schema version;
- an unbounded `String` fact type is not acceptable;
- no analytical fact may be persisted unversioned;
- Tenant scope must be explicit on every Tenant-derived fact;
- where both matter, a fact must distinguish operational occurrence time from
  analytical processing time.

Fact type and schema version exist so a persisted row stays interpretable and
evolvable after the code that wrote it has changed. They belong to the fact
contract rather than to a storage layout, so a later storage change cannot
silently strip them.

**Privacy.**

- collect the minimum analytical identity a documented purpose requires;
- raw JWT claims, provider subjects and bearer tokens are never analytical fact
  data;
- operational personal data must not be duplicated into analytical facts without
  a specific approved purpose;
- arbitrary payload structures are prohibited as fact storage;
- privacy-reduced facts must remain separated from any subject mapping they
  require;
- Tenant isolation must apply to both mappings and facts;
- analytics must not become employee scoring, ranking or disciplinary
  automation.

**Subject identity.**

Where correlation across facts is required, analytics resolves an opaque
analytics-owned subject key rather than copying an operational identifier:

```text
(tenantId, operationalSubjectId) -> analytical subject key
```

`V21` implements that mapping as analytics-owned persistence. Its durable
architectural purpose is Tenant-local pseudonymous subject resolution with no
cross-schema foreign key into operational aggregates, and PostgreSQL arbitrates
concurrent resolution of the same subject. Deletion and unlink semantics for the
mapping are unresolved, and must not corrupt operational records when
introduced.

**Persistence.**

- PostgreSQL remains the persistence technology;
- analytics owns its own schema and tables;
- no cross-schema foreign key may express analytics ownership over operational
  aggregates;
- structural invariants belonging to the database must have database-level
  constraint tests;
- no JVM-local lock is a correctness mechanism; where arbitration is required,
  PostgreSQL provides it.

**Retention.**

- analytics owns retention of analytical derivatives;
- retention must operate only on analytics-owned data;
- workforce audit evidence is immutable from analytics and must never be deleted
  or mutated by analytical retention;
- expiry derives from operational occurrence time, so a replayed ingestion
  cannot extend how long analytical data is retained;
- the retention window and purge execution remain subject to executable
  implementation evidence.

**Consistency and observability.**

- failure to record an analytical fact must never falsify whether the
  originating operational transaction committed;
- an autonomous `REQUIRES_NEW` analytical write must not create evidence
  inconsistent with the operational transaction;
- observability must use bounded dimensions only; Tenant, User, subject,
  resource and correlation identifiers must not become metric labels.

## Ingestion decision status

The workforce-to-analytics ingestion mechanism is **unresolved**.

This is a deliberate outcome, not an omission. Two properties of the current
workforce audit representation block a safe choice:

- the workforce append path does not expose an authoritative occurrence time to
  the application, so a fact produced at append time cannot carry one;
- the audit representation provides no proven commit-ordered cursor.

Consequently:

- synchronous direct push is currently unsuitable: it cannot supply occurrence
  time, and coupling analytical persistence failure to the workforce transaction
  violates the required failure semantics;
- best-effort push with swallowed errors is insufficient, because a committed
  workforce action could permanently lose its analytical representation with no
  recoverability;
- a non-durable application event lacks the required recovery and replay
  guarantees;
- a durable event registry or transactional outbox is premature, because it
  introduces durable-delivery infrastructure not yet justified by executable
  evidence;
- naive pull is currently unsuitable, because no commit-ordered cursor is
  proven.

Transaction timestamps are not proven commit-order cursors, random event
identity is not ordering, and a fixed overlap window cannot prove completeness
under arbitrarily long transactions.

The governing constraint is therefore:

> No workforce-to-analytics ingestion mechanism will be implemented until
> occurrence-time, consistency, failure, recovery, retry, idempotency and replay
> semantics are concretely proven.

That constraint is the design decision. The transport itself is not yet a design
decision. The remaining analytical foundation — fact schema identity, fact
persistence, classification, retention and observability — may proceed
independently, because none of it depends on how facts arrive.

## Alternatives rejected for now

Rejected under current evidence, not permanently prohibited:

- synchronous projection inside the operational transaction;
- swallowed best-effort projection;
- non-durable application event;
- durable event registry or transactional outbox ahead of justifying evidence;
- naive timestamp cursor;
- event-identity cursor;
- fixed overlap window;
- JVM-local locking as an ingestion correctness mechanism.

## Consequences

Positive:

- analytical ownership, privacy reduction and retention have one explicit home;
- typed and versioned facts will allow schema evolution without rewriting
  historical migrations;
- analytics cannot silently become authorization or operational truth;
- the fail-closed module declaration makes any future coupling reviewable;
- the transport remains replaceable, because no consumer depends on it yet.

Costs and trade-offs:

- ingestion implementation is blocked until stronger evidence exists;
- analytical completeness cannot be claimed, and no consumer may assume it;
- fact persistence, classification, retention execution, observability and
  recovery semantics all remain outstanding work;
- several issue #31 acceptance criteria therefore remain intentionally
  unsatisfied at this checkpoint.

This ADR records intent. It is not evidence that any of the above behaves as
described.

## Deferred

The following remain outside this decision until concrete evidence requires
them:

- Customer analytical facts beyond the currently approved workforce-focused
  slice;
- broker adoption, Kafka, RabbitMQ or equivalent messaging infrastructure;
- transactional outbox or durable event registry;
- change-data-capture;
- external warehouse, lake or lakehouse infrastructure;
- generalized analytics query or reporting APIs;
- employee scoring, ranking or disciplinary automation;
- any ingestion mechanism not yet proven against the constraints recorded above.

Deferred items require an explicit governed scope change rather than being
introduced implicitly while completing OH-016.

## Verification required before TESTED

ADR-0014 remains DESIGNED until executable evidence proves:

- analytical fact persistence with bounded fact type, explicit schema version
  and Tenant isolation;
- rejection of structurally malformed analytical state;
- privacy and adversarial behavior over persisted analytical rows;
- cross-Tenant isolation for both mappings and facts;
- ingestion consistency, failure, recovery, idempotency and replay semantics;
- retention and purge safety, including that workforce audit evidence is neither
  deleted nor mutated;
- analytics observability with bounded dimensions;
- Spring Modulith verification;
- PostgreSQL integration evidence from an empty database;
- `git diff --check` and full `mvnw clean verify`;
- required workflows on the exact candidate HEAD;
- final Codex review with no unresolved irregularity.

Issue #31 remains the authoritative full acceptance specification.

Only after that evidence exists may ADR-0014 become TESTED. Following the
ADR-0011 to ADR-0013 precedent, the documentation-only promotion commit is not
itself the reviewed implementation checkpoint and remains subject to its own
gates.

## References

This decision builds directly on:

- ADR-0005 — PostgreSQL Persistence and Transaction Boundaries;
- ADR-0011 — Identity Personas and Scoped Authorization Kernel, for the data and
  analytics governance objective and observability boundaries;
- ADR-0012 — Tenant Workforce Authority Lifecycle, for workforce audit evidence
  and its privacy boundary;
- ADR-0013 — Customer Account Binding and Ownership-Based Self-Service, for the
  Customer privacy boundary and deferred Customer analytics;
- PostgreSQL transaction and visibility semantics;
- LGPD purpose, necessity and proportionality principles.

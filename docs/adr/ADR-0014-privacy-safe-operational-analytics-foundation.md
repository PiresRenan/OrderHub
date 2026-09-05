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

OH-016 therefore establishes the durable analytical boundaries first, and commits
to a transport for operational evidence only once evidence supports choosing one.
That transport is now selected. It is a design selection, not yet proof that it
behaves as described.

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

The first workforce-to-analytics ingestion mechanism is selected in
"Workforce-to-analytics ingestion decision". The module remains declared
fail-closed until the reviewed dependency edge that mechanism requires is
introduced as an explicit change.

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
- the effective retention window is configuration supplied to the analytical
  retention catalog rather than schema identity, so a later legal or business
  change does not rewrite historical migrations;
- retention policy evaluation, Tenant-scoped purge, inclusive expiry at the
  policy boundary, repeated-purge idempotency and duplicate concurrent purge
  safety under PostgreSQL row-lock arbitration are executable;
- the production legal or business retention duration remains a configuration
  decision this ADR deliberately does not fix.

**Consistency and observability.**

- failure to record an analytical fact must never falsify whether the
  originating operational transaction committed;
- an analytical write that can commit while the originating operational
  transaction is still unresolved is prohibited, because a later rollback would
  leave analytical evidence for an operation that never happened; this is what
  the prohibition on an autonomous `REQUIRES_NEW` analytical write means;
- a transactional listener whose own transaction begins only after the source
  transaction has committed is permitted, because a rolled-back source
  transaction never makes it eligible; the executable invariant is that no
  analytical fact exists for a rolled-back source transaction;
- observability must use bounded dimensions only; Tenant, User, subject,
  resource and correlation identifiers must not become metric labels.

## Workforce-to-analytics ingestion decision

Status of this section: **SELECTED, NOT YET EXECUTABLY PROVEN.**

The first workforce-to-analytics ingestion uses Spring Modulith's persistent JDBC
Event Publication Registry.

**Shape of the decision.**

- workforce publishes a minimal opaque notification from inside the transaction
  that already performs the mutation and appends the audit evidence;
- the notification carries only `tenantId` and `auditEventId`;
- the registry records the publication as part of that same source transaction,
  so durable delivery intent and the operational change commit together;
- analytics consumes the notification only after the source transaction has
  committed, in a listener transaction of its own;
- the listener then reads the committed operational source through an explicit
  workforce-owned application contract addressed by `(tenantId, auditEventId)`;
- analytics never queries or imports workforce persistence;
- the `occurred_at` already persisted by `V16` becomes the occurrence time of the
  analytical fact;
- analytics resolves the operational Staff identities into analytical subject
  keys before persisting the fact.

**Why the earlier blockers no longer apply.**

This ADR previously recorded ingestion as unresolved, because the workforce
append path exposes no occurrence time to the application and the audit
representation proves no commit-ordered cursor. Both observations still hold.
They no longer block this mechanism:

- occurrence time is not produced at publish time. The listener reads it after
  commit from the operational row that already stores it, so no new timestamp is
  invented and no workforce contract has to grow one;
- the absence of a commit-ordered cursor still blocks any pull or scanning
  design. It is not applicable here, because each committed source transaction
  records its own durable publication and recovery is driven by publication
  identity and status rather than by ordering audit rows. The cursor problem is
  side-stepped, not solved.

**Occurrence time.**

`WorkforceAuditEvidence` still has no occurrence-time component. `V16` persists
`occurred_at` using PostgreSQL `CURRENT_TIMESTAMP`, which is transaction start
time — not statement time and not commit order. It is nevertheless the right
source here, because it is the marker the operational audit system of record
itself stores; analytics inherits that exact value rather than creating a second,
competing timestamp. Publication date, listener execution time, retry time and
analytical processing time are never occurrence time.

**Notification privacy.**

The registry serializes and durably stores the event. That makes the payload a
privacy decision, not an implementation detail. The stored notification must not
contain raw Staff identifiers, email, phone, display name, provider or JWT
subject, correlation identifiers, before or after organizational state, arbitrary
payload, or subject-mapping material. For this first producer it is limited to
`tenantId` and `auditEventId`, two opaque identifiers sufficient to locate the
committed operational source Tenant-safely. The registry is integration
infrastructure; it is not analytical fact storage.

**Workforce source contract.**

After commit the listener obtains a bounded, purpose-built projection through a
workforce-owned contract. It exposes only what the accepted analytical fact
needs: `tenantId`, `auditEventId`, actor and affected Staff identifiers, action,
outcome, reason code and `occurredAt`. It excludes correlation identifiers,
before and after organizational state, arbitrary payload, and every persistence
type — JDBC types, row mappers, result sets, entities and repositories.

Raw Staff identifiers may cross that in-process application contract, because
analytics needs them transiently to resolve pseudonyms. They must never be
persisted in the registry payload or in analytical fact storage.

**Module ownership.**

Workforce owns and publishes the notification, and does not depend on analytics.
Analytics may depend only on the reviewed workforce contract this projection
requires, and still may not import or query workforce persistence. The
fail-closed declaration was deliberately temporary: implementation will require
an explicit reviewed module dependency from analytics to the exported workforce
contract. That edge does not exist yet.

**Source transaction coupling.**

Recording durable delivery intent is part of the source transaction's consistency
contract. If the publication record cannot be stored, the workforce transaction
must not commit, because committing would create an operational change whose
analytical projection is permanently unrecoverable.

This does not put analytical fact persistence inside the workforce transaction.
Once the source transaction commits, projection failure cannot roll it back, the
fact is written in the listener's own transaction, and an incomplete publication
stays recoverable. The accepted trade-off is that source availability depends on
recording local delivery intent, while downstream analytical processing is
decoupled from source commit.

**Failure model.**

| Failure | Source transaction | Publication | Analytical fact | Recoverable |
| --- | --- | --- | --- | --- |
| publication record fails before commit | rolls back | none | none | no projection is owed |
| commits, listener not yet invoked | committed | durable | absent | yes |
| listener or fact persistence fails | committed | incomplete or failed | absent or rolled back | yes |
| process crash during the listener | committed | recoverable via publication lifecycle | absent | yes |
| successful retry | committed | completed | exactly one | — |
| same identity, divergent content | committed | — | rejected | fails closed |

The model is durable at-least-once projection over idempotent, fail-closed fact
persistence. It is not exactly-once transport, and must not be described as such.

**Recovery.**

Recovery uses the framework's own resubmission APIs for failed and incomplete
publications. This ADR selects explicit controlled resubmission over automatic
republication on application restart, which stays disabled: explicit recovery is
deterministic and testable, and avoids restart-driven ambiguity in multi-instance
deployments. No custom scheduler and no separate recovery table is introduced.

**Registry lifecycle and persistence.**

Completion mode is `DELETE`, so successfully processed notifications do not
accumulate; failed and incomplete publications are unaffected and remain
available for recovery. `ARCHIVE` would retain serialized integration payload
with no current requirement, and the default `UPDATE` would require separate
cleanup of completed rows. Registry completion is an integration lifecycle
concern and is not analytical retention.

The publication relation is framework infrastructure, and belongs in the default
schema rather than the analytics schema. It introduces no analytics relation, no
cross-schema foreign key, no new database and no new service.

**Schema authority.**

Flyway remains the schema authority. The framework's automatic JDBC schema
initialization will be disabled, and a later migration is expected to introduce
the required `event_publication` relation and its indexes from the official
Spring Modulith 2.1.1 PostgreSQL schema. That migration is expected to be `V23`;
it does not exist and is not accepted.

**Dependency.**

The intended addition is the single `spring-modulith-starter-jdbc` artifact,
versioned by the existing Spring Modulith BOM. It supplies the JDBC publication
registry and the default event serialization path, which targets the Jackson 3
mapper that Spring Boot 4 already provides. No custom serializer, no Jackson 2
dependency and no compatibility bridge is authorized. The dependency is not added
by this decision.

## Alternatives rejected for now

Rejected under current evidence, not permanently prohibited:

- synchronous analytical persistence inside the operational transaction, because
  analytical failure would then decide whether an operational change committed;
- swallowed best-effort projection, because a committed operational action could
  permanently lose its analytical representation with no recoverability;
- non-durable application events, which lack the required recovery guarantees;
- naive timestamp cursors, event-identity cursors and fixed overlap windows,
  because transaction timestamps are not commit-order cursors, random event
  identity is not ordering, and a fixed window cannot prove completeness under
  arbitrarily long transactions;
- a custom transactional outbox, because the selected local durable registry
  already provides publication durability and recovery, and a bespoke outbox
  would reimplement it with more code and no additional guarantee;
- brokers such as Kafka or RabbitMQ, and change-data-capture, because they add
  operational infrastructure to solve a problem a single local relation already
  solves within one deployment;
- JVM-local locking as an ingestion correctness mechanism.

## Consequences

Positive:

- analytical ownership, privacy reduction and retention have one explicit home;
- typed and versioned facts allow schema evolution without rewriting historical
  migrations;
- analytics cannot silently become authorization or operational truth;
- the fail-closed module declaration makes the coupling this mechanism needs a
  reviewable change rather than an accidental import;
- durable recovery is obtained without a broker, a custom outbox or
  change-data-capture;
- the durable notification keeps a minimal privacy surface, because the payload
  is two opaque identifiers rather than operational evidence;
- no workforce commit-order cursor is required for this ingestion family;
- the operational `occurred_at` remains authoritative, so analytical retention
  continues to derive from when the operation happened;
- a source transaction cannot commit without recording durable delivery intent;
- analytical failure after that commit cannot roll the source transaction back.

Costs and trade-offs:

- source availability now depends on successfully persisting delivery intent
  locally;
- one framework infrastructure relation is expected to be added later;
- one Spring Modulith JDBC starter is expected to be added later;
- listener processing is at-least-once, so correctness relies on the accepted
  idempotent and fail-closed fact repository;
- explicit recovery is operational behaviour that must itself be tested;
- production ingestion is still not implemented, so analytical completeness
  cannot be claimed and no consumer may assume it;
- several issue #31 acceptance criteria therefore remain unsatisfied at this
  checkpoint.

This ADR records intent and a selected mechanism. It is not evidence that any of
the above behaves as described.

## Deferred

The following remain outside this decision until concrete evidence requires
them:

- Customer analytical facts beyond the currently approved workforce-focused
  slice;
- broker adoption, Kafka, RabbitMQ or equivalent messaging infrastructure;
- a custom transactional outbox;
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
- retention and purge safety, including that workforce audit evidence is neither
  deleted nor mutated;

and, for the selected ingestion mechanism specifically:

- the workforce notification publication joins the source transaction;
- a rolled-back source transaction leaves no durable analytical fact;
- the registry payload carries only the approved opaque notification fields;
- the post-commit listener reads the operational source only through the
  explicit workforce contract;
- the `occurred_at` persisted by `V16` is preserved into the analytical fact;
- a committed source operation survives listener and fact-persistence failure;
- a failed publication is recoverable by explicit resubmission;
- recovery converges on exactly one semantic fact;
- the same identity with divergent content remains fail-closed;
- the analytics-to-workforce module dependency is explicit and acyclic;
- the `event_publication` schema matches the framework's own PostgreSQL schema
  and is owned by Flyway;
- automatic framework schema initialization is disabled;
- completion mode and recovery semantics behave as designed;
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

The selected ingestion mechanism is governed by:

- Spring Modulith 2.1.1 — Working with Application Events, for the Event
  Publication Registry, publication lifecycle, transactional listener semantics
  and JDBC support;
- Spring Modulith 2.1.1 — configuration appendix, for completion mode, JDBC
  schema initialization and restart republication properties;
- Spring Boot 4 — Jackson 3 as the default JSON mapper.

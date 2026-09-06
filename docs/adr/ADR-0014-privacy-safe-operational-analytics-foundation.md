# ADR-0014 — Privacy-Safe Operational Analytics Foundation

Status: TESTED

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
That transport is selected, implemented and executably proven against a reviewed
implementation checkpoint carrying no unresolved review finding.

## Decision

`analytics` is established as a first-class Spring Modulith module, and is
assigned ownership of analytical fact schemas, analytical subject identity, data
classification, retention and derived analytical projections.

Analytics will consume operational facts only through explicit application
contracts. It owns its own PostgreSQL schema, and does not participate in
operational correctness.

The module was declared fail-closed, permitted no dependency on any other
application module, so the cross-module edge ingestion needs had to be an
explicit reviewed change rather than an accidental import. It now declares
exactly one dependency, on the workforce named interface that carries the
notification and the bounded source contract.

Customer semantics are present in the integrated baseline. That does not admit
Customer analytical facts into this slice; doing so requires an explicit
governed scope change.

The first workforce-to-analytics ingestion mechanism is described in
"Workforce-to-analytics ingestion decision", and is implemented.

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

Status of this section: **SELECTED, IMPLEMENTED AND EXECUTABLY PROVEN.**

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
fail-closed declaration was deliberately temporary: the implementation
required an explicit reviewed module dependency from analytics to the exported
workforce contract. That edge now exists and is declared as
`workforce :: authority-change-analytics-source`, naming the interface rather
than the module so workforce persistence, configuration and internal services
stay unreachable.

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
publications, in two distinct roles.

The **production baseline** is automatic republication of outstanding
publications when an instance starts, enabled through
`spring.modulith.events.republish-outstanding-events-on-restart`. A process can
stop after committing a publication but before its asynchronous listener
completes, and OrderHub deliberately exposes only the health endpoint, so no
remote surface exists that an operator could call to drive resubmission.
Startup republication is therefore the recovery path a deployment actually has.

An earlier revision of this decision disabled it and described recovery as
"explicit and controlled". That was not deployable: the explicit API was
reachable only from a test, so a crash between commit and projection could leave
an analytical fact missing indefinitely. Adding an administrative endpoint
instead was rejected, because it would introduce a privileged, externally
reachable management contract with its own authentication, authorization and API
governance, purely to recover framework publications.

Explicit `FailedEventPublications` resubmission remains a **tested framework
primitive** rather than OrderHub's production recovery surface. It is exercised
directly so the recovery mechanism is proven at the API level as well as through
a restart.

Startup republication reopens the multi-instance ambiguity the earlier revision
avoided: several instances may republish concurrently, and delivery stays
at-least-once. That is accepted rather than coordinated away, because
convergence is already the responsibility of analytical fact identity
(`tenantId` + `sourceEventId` + `factType`) with idempotent exact replay and
fail-closed divergent duplicates. No distributed recovery coordinator, custom
scheduler and no separate recovery table is introduced.

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
initialization is disabled, and `V23` introduces the required
`event_publication` relation and its indexes from the official Spring Modulith
2.1.1 PostgreSQL schema. No further migration was required to complete
ingestion.

**Dependency.**

The intended addition is the single `spring-modulith-starter-jdbc` artifact,
versioned by the existing Spring Modulith BOM. It supplies the JDBC publication
registry and the default event serialization path, which targets the Jackson 3
mapper that Spring Boot 4 already provides. No custom serializer, no Jackson 2
dependency and no compatibility bridge is authorized. That single artifact is the
only dependency the completed ingestion added.

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
- recovery is operational behaviour that must itself be tested, both as startup
  republication and as explicit API resubmission, and is;
- the analytical vocabulary models only the workforce actions a concrete
  workflow produces, so audit evidence outside it is deliberately not projected
  and analytical completeness is bounded by that vocabulary rather than by the
  workforce audit vocabulary;
- asynchronous annotation processing is now enabled application-wide, because
  the selected listener semantics depend on it.

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

## Executable evidence

**TESTED against implementation checkpoint
`d63d6a362379a303de1c02d3982420fdd524c033`.**

That checkpoint carries:

| Gate | Result |
| --- | --- |
| Local `mvnw clean verify` | BUILD SUCCESS — 957 tests, 0 failures, 0 errors, 0 skipped |
| Spring Modulith verification | 10 tests green across four module-contract classes |
| Flyway from an empty PostgreSQL 18.6 database | reaches `v23` |
| Branch Policy | SUCCESS |
| CI | SUCCESS |
| Platform CI | SUCCESS |
| Codex review on that exact SHA | no findings |
| Unresolved review threads | none |

The mechanism is proven by executable tests against a real PostgreSQL database
migrated from empty through `V23`, the real Spring Modulith 2.1.1 JDBC Event
Publication Registry, the real workforce services and the real analytics
listener. Only analytical fact persistence can be made to fail on demand, by a
test-owned decorator, because no deployment can be asked to produce that
condition reliably.

| # | Invariant | Executable evidence |
| --- | --- | --- |
| 1 | Publication registration joins the source transaction | `WorkforceAuthorityChangePublicationTransactionTest` |
| 2 | A rolled-back source leaves no durable publication | `WorkforceAuthorityChangePublicationTransactionTest` |
| 3 | Real registry persistence failure rolls back mutation and audit | `WorkforceAuthorityChangePublicationFailureAtomicityTest` |
| 4 | Durable payload is exactly `tenantId` and `auditEventId` | `WorkforceAuthorityChangePublicationTransactionTest` |
| 5 | Listener is after-commit with its own transaction | `WorkforceAuthorityChangeIngestionE2ETest` |
| 6 | A committed source survives projection failure | `WorkforceAuthorityChangeIngestionE2ETest` |
| 7 | Source is read only through the workforce contract | `PostgreSqlWorkforceAuthorityChangeAnalyticsSourceTest`, `WorkforceAuthorityChangeIngestionE2ETest` |
| 8 | `occurred_at` comes from the committed audit row | `WorkforceAuthorityChangeIngestionE2ETest` |
| 9 | Staff identifiers cross only transiently | `WorkforceAuthorityChangeIngestionE2ETest` |
| 10 | Persisted analytics carries only pseudonymous subjects | `WorkforceAuthorityChangeIngestionE2ETest`, `PostgreSqlAnalyticalSubjectPseudonymRepositoryTest` |
| 11 | Translation uses the bounded analytical vocabulary | `WorkforceAuthorityChangeIngestionE2ETest` |
| 12 | Successful projection completes the publication | `WorkforceAuthorityChangeIngestionE2ETest` |
| 13 | Failed projection retains a recoverable publication | `WorkforceAuthorityChangeIngestionE2ETest` |
| 14 | Framework resubmission recovers it | `WorkforceAuthorityChangeIngestionE2ETest` |
| 15 | Retry converges on one fact | `WorkforceAuthorityChangeIngestionE2ETest` |
| 16 | Divergent duplicate fails closed | `PostgreSqlWorkforceAuthorityChangeFactRepositoryTest` |
| 17 | `DELETE` completion removes successful publications | `WorkforceAuthorityChangeIngestionE2ETest`, `PlatformEventPublicationRegistryRuntimeConfigurationTest` |
| 18 | Restart republication enabled as the deployable recovery baseline | `PlatformEventPublicationRegistryRuntimeConfigurationTest` |
| 19 | Observability carries no high-cardinality identifier | `WorkforceAuthorityChangeIngestionE2ETest` |
| 20 | Module edge explicit and acyclic | `AnalyticsModuleContractTest`, `OrderHubModularityTests` |
| 21 | Flyway owns `event_publication`; empty database bootstraps to `V23` | `PostgreSqlEventPublicationRegistrySchemaTest`, full suite |
| 22 | Deployable crash and restart recovery: an outstanding publication is automatically republished by the next application startup against the same database | `WorkforceAuthorityChangeRestartRecoveryE2ETest` |
| 23 | Analytical fact schema, classification and retention | `PostgreSqlWorkforceAuthorityChangeFactSchemaTest`, `PostgreSqlWorkforceAuthorityChangeFactConstraintsTest`, `PostgreSqlWorkforceAuthorityChangeFactRetentionTest`, `AnalyticalRetentionPolicyCatalogTest` |

Delivery semantics are **at-least-once delivery over idempotent, fail-closed
projection persistence**. This is deliberately not exactly-once transport and
must not be described as such: recovery may deliver a notification again, and
correctness comes from the fact repository accepting an exact replay and
rejecting a divergent one.

**Analytical vocabulary boundary.**

Only `POSITION_CHANGED`, `POSITION_AUTHORITY_CHANGED` and `PRIVILEGED_MUTATION`
are produced by a concrete workforce workflow today, and those are exactly the
actions the analytical vocabulary models. `STAFF_ACTIVATED`,
`STAFF_DEACTIVATED`, `DEPARTMENT_CHANGED` and `SUPERVISOR_CHANGED` are reachable
only through generic audit infrastructure with no current production caller.
They are therefore a deliberate non-projecting success: the notification is
acknowledged, no fact is written and the publication completes. Treating them as
failures would create publications that can never succeed; inventing analytical
meaning for them would put semantics in the fact contract that no analytical
purpose asked for. Admitting one later is an explicit change here and in the
analytical vocabulary.

**Recovery.**

Recovery is explicit and uses the framework's own
`org.springframework.modulith.events.FailedEventPublications.resubmit(ResubmissionOptions)`,
implemented by `PersistentApplicationEventMulticaster`, which also implements
`IncompleteEventPublications`.

The production baseline is that same multicaster's
`afterSingletonsInstantiated()` startup hook: it reads
`spring.modulith.events.republish-outstanding-events-on-restart` and, when true,
calls `resubmitIncompletePublications(...)`. The JDBC repository selects
outstanding work as `COMPLETION_DATE IS NULL OR STATUS = 'FAILED'`, so a
publication left `FAILED` by a projection failure is republished on the next
startup. No custom scheduler, recovery table, retry queue, administrative
endpoint or outbox exists.

**Binding precondition on enabling retention.**

Analytical retention is deliberately not wired in this slice: no policy catalog,
retention service or purge schedule is instantiated, because the effective
window is a legal and business decision this ADR does not fix.

Review raised a real interaction that becomes reachable the moment it is wired.
A purge deletes an expired fact by occurrence time; if a publication for that
same source event is still outstanding, a later replay recreates the fact with
its original `occurred_at`. Retention is not extended — the deadline is
unchanged and the next purge removes it again — but the row is present again
after its deadline, for up to one purge cycle.

Wiring retention therefore requires resolving that interaction first, and each
available remedy carries a decision this slice must not make implicitly:

- refusing to project a source that is already past its retention deadline
  requires fixing the retention window now, which is exactly the decision
  deferred above;
- keeping a retained idempotency marker requires a new relation that stores
  `source_event_id` beyond the fact's own lifetime, which is itself a privacy
  cost and a new migration;
- having retention also complete the outstanding publication would make
  analytics operate the shared integration registry, crossing a boundary this
  ADR forbids.

Until one of those is chosen, retention stays unwired and the interaction stays
unreachable. This is recorded as a precondition rather than resolved, because
resolving it silently would decide the retention window as a side effect.

**Review history.**

The reviewed path to this checkpoint corrected three real defects rather than
only accumulating evidence, and each correction carries its own executable
proof:

- restart recovery was not deployable. Republication on restart was disabled
  while the only resubmission path was reachable from a test, so a crash between
  commit and projection could lose a fact permanently. Corrected by enabling
  startup republication and proving it across two application lifecycles;
- concurrent first-time projections with reversed subjects could deadlock on the
  subject mapping rows. Corrected by resolving both mappings in one global
  order;
- the projection metric was recorded before the listener transaction committed,
  so a transaction that failed at commit reported a successful projection.
  Corrected by recording the result from transaction completion.

A fourth finding, the interaction between analytical retention and durable
replay, is recorded above as a binding precondition on wiring retention rather
than resolved, because retention is not instantiated in this slice and every
remedy would decide something this ADR defers. Review of the checkpoint accepted
that boundary.

**Residual conditions.**

Issue #31 remains the authoritative acceptance specification. Merge into the
integration branch remains an explicit maintainer decision outside this
document.

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

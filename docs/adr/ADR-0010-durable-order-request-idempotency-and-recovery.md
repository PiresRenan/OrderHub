# ADR-0010 — Durable Order Request Idempotency and Recovery

Status: DESIGNED

## Context

OH-011 completed the atomic Order + Catalog validation + Inventory commitment
boundary.

The `pre-release` baseline for OH-012 is:

`93c004f82e11e61885ffc969b6a8d1eb9e901a24`

The create-Order transaction is now internally atomic, but the public
`POST /orders` operation is still vulnerable to an ambiguous client outcome.

A representative failure is:

```text
client
  |
  | POST /orders
  v
OrderHub
  |
  | BEGIN
  | create Order
  | validate Catalog
  | commit Inventory
  | COMMIT
  v
PostgreSQL

HTTP response is lost

client retries POST /orders

OrderHub currently generates another Order ID
and may commit the same commercial intent again
```

Transaction atomicity alone cannot solve this problem because the uncertainty is
between the client and the already committed server transaction.

OH-012 establishes durable idempotency before introducing automatic retries,
transactional outbox publication or external integrations.

## Primary invariant

For one trusted Tenant, one versioned operation and one accepted idempotency key,
at most one successful durable Order-creation outcome may exist.

For the same durable key identity:

- the same canonical request replays the original successful outcome;
- a different canonical request is rejected;
- concurrent replicas cannot independently execute the business effect;
- rollback cannot leave a false successful idempotency record;
- a successfully committed Order/Inventory effect cannot lose the durable
  information required for later replay.

The invariant must hold across application restart and multiple OrderHub JVMs
sharing PostgreSQL.

## Research evidence — 2026-09-01

### Idempotency-Key design precedent

The IETF HTTPAPI working group published
`draft-ietf-httpapi-idempotency-key-header-07` on 2025-10-15.

It describes `Idempotency-Key` as a client-provided request header intended to
make non-idempotent methods such as POST fault-tolerant and discusses key
uniqueness, fingerprints and retry behavior.

The draft expired on 2026-04-18 and is not an active RFC.

OrderHub therefore adopts the field name as useful design precedent but does not
claim that OH-012 implements a current IETF standard.

References:

- https://datatracker.ietf.org/doc/draft-ietf-httpapi-idempotency-key-header/
- https://datatracker.ietf.org/doc/html/draft-ietf-httpapi-idempotency-key-header-07

### PostgreSQL uniqueness and conflict arbitration

PostgreSQL 18 enforces uniqueness through unique indexes.

`INSERT ... ON CONFLICT` uses a unique constraint/index as conflict arbiter.
Conflicting insertions may wait for the transaction owning the conflicting key.

This provides database-owned coordination across JVM replicas without Redis or
application-local mutexes.

References:

- https://www.postgresql.org/docs/18/sql-insert.html
- https://www.postgresql.org/docs/18/index-unique-checks.html

### Existing transaction ownership

OH-011 already established one framework-neutral create-Order transaction
boundary.

OH-012 extends that same transaction rather than nesting a second independent
business transaction around it.

The application/domain layers remain free of Spring transaction APIs.

## Decision

### 1. Scope OH-012 to Orders

Do not introduce a generic root `idempotency` module.

The only concrete operation currently requiring durable request idempotency is:

```text
POST /orders
```

The first implementation therefore belongs to the Orders module.

A reusable cross-module abstraction may be extracted later only when a second
real operation demonstrates common semantics.

### 2. Public HTTP contract

`POST /orders` requires exactly one `Idempotency-Key` header.

The key is:

- client generated;
- opaque to OrderHub business logic;
- case-sensitive;
- not a bearer credential;
- not a Tenant selector;
- scoped only after `TrustedTenantContext` has been established.

Initial technical constraints:

- one logical header value;
- length from 1 through 128 characters;
- visible ASCII only;
- whitespace and ASCII control characters are rejected;
- comma is rejected to avoid ambiguous multi-value normalization;
- the value is never trimmed or case-folded.

UUID or another high-entropy client identifier is recommended but no specific
identifier format is required.

The 128-character maximum is a provisional resource/safety policy, not a measured
production capacity claim.

### 3. Stable Problem Details

Missing or invalid key:

```text
400 Bad Request
type   = urn:orderhub:problem:idempotency-key-invalid
code   = IDEMPOTENCY_KEY_INVALID
detail = The request idempotency key is missing or invalid.
```

Completed key reused with another request:

```text
422 Unprocessable Content
type   = urn:orderhub:problem:idempotency-key-reused
code   = IDEMPOTENCY_KEY_REUSED
detail = The request idempotency key cannot be reused for different request content.
```

Concurrent owner exceeding the bounded wait:

```text
409 Conflict
type   = urn:orderhub:problem:idempotency-request-in-progress
code   = IDEMPOTENCY_REQUEST_IN_PROGRESS
detail = A request with this idempotency key is still being processed.
```

None of these responses exposes raw keys, fingerprints or owner identifiers.

### 4. Successful replay

A completed successful request is replayed as the same successful creation
contract:

```text
HTTP 201 Created
same Order ID
same creation status
same Inventory allocation outcome
same Tenant / Customer / item representation
```

Replay does not invoke Order persistence, Catalog validation or Inventory
commitment again.

OH-012 introduces no custom replay response header yet.

### 5. Durable key identity

The raw key is not persisted.

OrderHub computes:

```text
keyDigest = SHA-256(UTF-8(Idempotency-Key))
```

The durable identity is:

```text
tenantId
+ operation
+ keyDigest
```

The initial operation identifier is:

```text
CREATE_ORDER_V1
```

The database must enforce uniqueness of this durable identity.
### 6. Canonical request fingerprint

Raw JSON bytes are not fingerprinted.

Whitespace, JSON object property order and serializer formatting are transport
details rather than Order business identity.

Fingerprinting occurs after the request has been parsed and validated into the
application command.

The canonical fingerprint includes:

```text
fingerprintVersion = 1
operation          = CREATE_ORDER_V1
tenantId
customerId
itemCount
for each item in list order:
    itemIndex
    variantId
    quantity
```

UUIDs use their canonical 128-bit value rather than locale-dependent formatted
text.

Integer quantities use their exact integer value.

The canonical preimage is encoded with explicit field boundaries/lengths before
SHA-256 is applied; ambiguous delimiter concatenation is forbidden.

The digest is:

```text
SHA-256(canonical business-command encoding)
```

#### Item ordering decision

OH-012 does not sort or aggregate Order items for fingerprinting.

The current public Order representation preserves item sequence and multiplicity.

Changing:

```text
[A, B]
```

to:

```text
[B, A]
```

or:

```text
[A quantity=1, A quantity=1]
```

to:

```text
[A quantity=2]
```

is therefore not silently declared equivalent by the idempotency layer.

If Order semantics are normalized in a future domain change, the fingerprint
version must evolve with that contract.

### 7. Persistence model

Introduce an Orders-owned PostgreSQL table conceptually equivalent to:

```text
orders.order_request_idempotency

tenant_id
operation
key_digest
request_fingerprint
state
order_id
order_status
allocation_outcome
created_at
completed_at
```

Key/fingerprint digests use fixed 32-byte SHA-256 values.

The uniqueness boundary is:

```text
UNIQUE (
    tenant_id,
    operation,
    key_digest
)
```

The table is Orders-owned persistence.

No cross-module foreign key is introduced.

A same-module Order FK may be used only if it preserves the required insertion
and completion sequence without weakening transaction semantics.

### 8. Transaction-local state machine

The state model is intentionally small:

```text
ABSENT
  |
  | INSERT PROCESSING
  v
PROCESSING
  |
  | successful Order + Catalog + Inventory work
  | store response outcome
  v
COMPLETED
  |
  | COMMIT
  v
durable COMPLETED
```

Failure path:

```text
ABSENT
  |
  v
PROCESSING
  |
  | any business/technical failure
  v
ROLLBACK
  |
  v
ABSENT
```

`PROCESSING` is never intentionally committed.

This is a critical design property.

It means OH-012 does not need:

- ownership leases;
- stale-PENDING reclamation;
- lease heartbeats;
- replica clock synchronization;
- cleanup of abandoned claims;
- a distributed lock service.

Every committed idempotency row represents a completed successful create-Order
outcome.

### 9. Acquisition algorithm

Idempotency acquisition is the first database coordination operation inside the
create-Order transaction.

Conceptually:

```text
BEGIN

INSERT idempotency(
    tenant,
    operation,
    keyDigest,
    fingerprint,
    PROCESSING)
ON CONFLICT DO NOTHING
RETURNING ownership
```

If insertion succeeds:

```text
this transaction owns first execution
```

If a committed row already exists:

```text
read immutable completed row

if fingerprint equal:
    replay

if fingerprint different:
    422
```

If another transaction currently owns an uncommitted conflicting insertion,
PostgreSQL uniqueness arbitration coordinates the sessions.

The acquisition statement uses a dedicated PostgreSQL lock-wait boundary rather
than borrowing the total create-Order transaction timeout.

The adapter temporarily applies the externally configured
`orderhub.orders.idempotency.acquisition-timeout`.

The initial value is `500ms`. It is a provisional safety policy, not a measured
latency SLA.

The previous transaction-local `lock_timeout` is restored immediately after a
normal acquisition statement completes and before later Catalog or Inventory
work begins.

If PostgreSQL reports SQLSTATE `55P03` while the acquisition statement is waiting
for a lock, the idempotency adapter translates that acquisition-scoped failure
into the public in-progress conflict contract.

This classification is deliberately statement-scoped. SQLSTATE alone does not
identify the precise lock object, so OrderHub does not claim stronger lock
provenance than PostgreSQL reports.

Later Catalog/Inventory lock waits occur after the prior `lock_timeout` has been
restored and are therefore not translated by the idempotency adapter into
idempotency in-progress conflicts.

The total create-Order transaction timeout remains an independent outer safety
boundary.

### 10. Business execution after ownership

Only the transaction that successfully inserted the PROCESSING row may generate
a new Order ID and execute Order creation.

The desired ordering becomes:

```text
validate HTTP request/key
build application command
compute key digest/fingerprint

BEGIN
    acquire idempotency identity

    if REPLAY:
        reconstruct original successful result
        COMMIT/return

    generate Order ID
    persist Order
    validate/lock Catalog
    commit Inventory
    persist Inventory commitments
    complete idempotency record
COMMIT

return 201
```

Generating Order IDs before idempotency ownership is avoided.
### 11. Atomic completion

The transition to `COMPLETED` occurs inside the same physical transaction as:

- Order persistence;
- Catalog stabilization;
- Inventory mutation;
- Inventory commitment persistence.

Therefore these states are forbidden:

```text
Order + Inventory committed
but successful idempotency record absent
```

and:

```text
successful idempotency record committed
but Order / Inventory rolled back
```

A completion persistence failure rolls back the entire business transaction.

### 12. Stored replay projection

Do not persist the HTTP JSON response body.

The completed record stores only generated/stable fields required to reconstruct
the original create result, initially:

- Order ID;
- Order status at successful creation;
- Inventory allocation outcome.

Tenant, Customer and item representation come from the replay request after the
fingerprint has proven that the application command is identical.

This prevents the idempotency persistence model from becoming an accidental HTTP
serialization store.

### 13. Failure classification

#### HTTP/request validation

Missing/invalid header or invalid request body occurs before acquisition.

No idempotency row is written.

#### Catalog business rejection

The transaction rolls back.

No completed idempotency record remains.

A later retry re-evaluates current Catalog state.

#### Inventory business rejection

The transaction rolls back.

No completed idempotency record remains.

A later retry re-evaluates current Inventory state.

#### Technical database/application failure

The transaction rolls back.

No completed idempotency record remains.

A later client retry may execute again.

#### Successful create

The completed idempotency record is durable and permanently owns that key identity
under the current retention policy.

### 14. Retention

OH-012 introduces no automatic expiry or cleanup job.

Completed idempotency identities are retained indefinitely in the initial
implementation.

Reasons:

- no measured storage pressure currently exists;
- deleting a record makes the old key executable again;
- an arbitrary TTL silently weakens the public duplicate-prevention guarantee;
- safe expiry must account for concurrent requests and documented client retry
  expectations.

Future retention requires a separate evidence-backed policy or ADR amendment that
defines a minimum replay window before any deletion is implemented.

### 15. Multi-Tenant isolation

The trusted Tenant UUID is part of the durable unique key.

The same raw `Idempotency-Key` may therefore be used independently by different
Tenants.

The HTTP header can never change or infer Tenant context.

All idempotency reads/writes are explicitly tenant scoped.

### 16. Privacy and security

Raw idempotency keys are not:

- persisted;
- logged;
- emitted through Problem Details;
- placed in metrics;
- included in traces as ordinary attributes.

Request fingerprints are likewise not exposed.

Idempotency keys are not authentication credentials and possession of another
Tenant's key does not grant access to that Tenant or result because trusted
Tenant context remains mandatory.

### 17. Observability

Add bounded outcome metrics only.

Initial metric:

```text
orderhub.orders.idempotency{outcome=...}
```

Allowed outcomes:

```text
first_execution
replay
fingerprint_conflict
in_progress_conflict
technical_failure
```

Never label metrics with:

- raw key;
- key digest;
- request fingerprint;
- Tenant ID;
- Order ID;
- Customer ID;
- Variant ID.

Existing request/correlation tracing remains responsible for individual
diagnostics.

The existing successful creation metric:

`orderhub.orders.create.allocation{outcome=...}`

counts only `FIRST_EXECUTION` results that actually executed Order creation and
Inventory allocation.

A durable `REPLAY` is still a successful HTTP response, but it must not increment
that creation/allocation metric because no new Order or Inventory effect occurred.

Replay request volume is represented independently by:

`orderhub.orders.idempotency{outcome=replay}`

This separation prevents client retry volume from inflating Order creation and
Inventory allocation throughput dashboards.

### 18. No automatic retry framework

OH-012 enables clients to retry safely.

It does not cause OrderHub itself to begin broad automatic retries.

Automatic database or integration retry policy remains deferred until each retry
class has a proven safety model.
## Alternatives rejected

### Do nothing and rely on clients not retrying

Rejected because a lost HTTP response after commit creates an inherently
ambiguous outcome.

### Client-generated Order ID as the only idempotency mechanism

Rejected because resource identity and request replay semantics are separate
contracts, and OrderHub still needs fingerprint mismatch detection and stable
recovery behavior.

### Raw JSON hashing

Rejected because insignificant transport serialization differences would create
false mismatches.

### Sort/aggregate Order items in the fingerprint

Rejected for OH-012 because the current Order contract preserves list sequence and
multiplicity. The idempotency layer must not silently redefine domain semantics.

### Store raw idempotency key

Rejected because the raw value is unnecessary once a collision-resistant digest
is available and should not become additional durable request metadata.

### Redis SETNX / distributed cache

Rejected because PostgreSQL already owns the business transaction and durable
consistency boundary.

### JVM-local lock

Rejected because it cannot coordinate multiple OrderHub replicas.

### External distributed lock service

Rejected because database uniqueness already provides a durable cross-replica
arbiter for this invariant.

### Commit PENDING before the business transaction

Rejected because it creates stale ownership after crash and requires
leases/reclamation/time-based coordination.

The transaction-local PROCESSING design has no committed orphan ownership.

### SERIALIZABLE globally

Rejected because no demonstrated anomaly requires global serializable isolation.

### Cache every business rejection

Rejected because Catalog and Inventory state can legitimately change between
attempts.

OH-012 initially durably replays successful creation only.

### Expire keys after an arbitrary TTL

Rejected because an unmeasured TTL silently allows a previously completed request
to execute again after deletion.

### Persist full HTTP response JSON

Rejected because it couples durable application consistency to an adapter-specific
serialization representation.

## Implemented structure

The implemented responsibilities after RED/GREEN verification are:

Likely structure:

```text
orders.adapter.in.web
    Idempotency-Key parsing / HTTP mapping

orders.application
    key value / canonical fingerprint
    orchestration outcome

orders.application.port.out
    durable idempotency persistence boundary

orders.adapter.out.persistence.postgresql
    PostgreSQL acquisition/completion

orders.application.service.CreateOrderService
    idempotency acquisition before Order ID generation
    replay or first execution
```

No new root module is planned.

A new forward-only Flyway migration begins at V11.

V1-V10 remain immutable.

## Implementation evidence — 2026-09-02

OH-012 was implemented incrementally from the OH-011 `pre-release` merge base.

Final functional checkpoint before adversarial hardening:

`dd3a17053d88fd645b7b91a309d3e00227e2694e`

Final local adversarial-hardening checkpoint:

`cef34379ed7bc5a5f3c50a246fc0f3549b2f3e29`

Local executable evidence includes:

- exactly one required `Idempotency-Key` at the HTTP boundary;
- SHA-256 raw-key identity propagation without raw-key persistence;
- deterministic versioned canonical business-request fingerprinting;
- PostgreSQL V11 schema with database-enforced relational constraints;
- PostgreSQL unique-key arbitration across concurrent transactions;
- owner commit followed by replay;
- owner rollback followed by contender/new retry acquisition;
- dedicated acquisition-only PostgreSQL lock timeout and restoration;
- idempotency acquisition before Order ID generation;
- replay without repeated Order, Catalog or Inventory effects;
- stable privacy-safe 400, 409, 422 and technical-failure mappings;
- authenticated HTTP first execution/retry/replay against real PostgreSQL;
- successful same-key retry after business rollback;
- completion-persistence failure injection proving atomic rollback of Order,
  Inventory and transient PROCESSING state;
- same-key/same-request correctness across two independent OrderHub JVMs sharing
  PostgreSQL;
- transport JSON whitespace/property ordering excluded from fingerprint identity;
- bounded idempotency metrics with no business/idempotency identity labels;
- no Redis, broker, distributed mutex or JVM-local correctness lock.

Final local suite:

`714 tests, 0 failures, 0 errors, 0 skipped`

`git diff --check` is clean.

ADR status intentionally remains `DESIGNED` because pull-request checks and
independent final review are still pending.
## Verification plan

ADR-0010 remains `DESIGNED` until every required item below has executable
evidence.

### HTTP contract

- [ ] missing `Idempotency-Key` is rejected with stable privacy-safe 400;
- [ ] malformed/oversized key is rejected with stable privacy-safe 400;
- [ ] raw key is never echoed in Problem Details;
- [ ] first successful request returns 201;
- [ ] same key/same fingerprint successful retry returns the original 201 result;
- [ ] same key/different fingerprint returns stable 422;
- [ ] bounded same-key ownership contention maps specifically to stable 409;
- [ ] unrelated Catalog/Inventory technical lock failures are not mislabeled as
      idempotency in-progress conflicts.

### Fingerprint

- [ ] JSON whitespace/property order cannot change the fingerprint after parsing;
- [ ] Tenant changes the durable identity;
- [ ] Customer changes the fingerprint;
- [ ] Variant identity changes the fingerprint;
- [ ] quantity changes the fingerprint;
- [ ] item list order changes the fingerprint under the current contract;
- [ ] duplicate-line multiplicity changes the fingerprint;
- [ ] fingerprint encoding is deterministic across repeated JVM executions;
- [ ] SHA-256 digest length is database constrained.

### Atomicity

- [ ] successful Order + Inventory + idempotency completion use one physical
      transaction;
- [ ] Order persistence failure leaves no idempotency completion;
- [ ] Catalog rejection leaves no idempotency completion;
- [ ] Inventory rejection leaves no idempotency completion;
- [ ] Inventory technical failure leaves no idempotency completion;
- [ ] idempotency completion persistence failure rolls back Order and Inventory;
- [ ] no committed PROCESSING row can survive rollback/crash simulation.

### Concurrency

- [ ] same Tenant/same key/same request across two connections produces exactly
      one business execution;
- [ ] concurrent loser replays after the owner commits when completion occurs
      within the bounded wait;
- [ ] same Tenant/same key/different request cannot produce two Orders;
- [ ] owner rollback allows one waiting contender to become first executor;
- [ ] same raw key in different Tenants remains independent;
- [ ] deliberate same-key blocking terminates within the finite timeout;
- [ ] correctness holds across two independent OrderHub JVM replicas.

### Recovery

- [ ] committed Order followed by simulated lost HTTP response can be recovered by
      retry without another Order;
- [ ] recovered retry creates no additional InventoryCommitment;
- [ ] replay survives application restart because PostgreSQL is source of truth;
- [ ] replay result preserves original Order ID, creation status and allocation
      outcome.

### Persistence and architecture

- [ ] V1-V10 remain byte-for-byte unchanged;
- [ ] V11 builds idempotency persistence from an empty real PostgreSQL database;
- [ ] database uniqueness enforces Tenant + operation + key digest;
- [ ] database constraints reject malformed digest/state combinations;
- [ ] raw key is absent from durable schema;
- [ ] no Redis/broker/distributed lock is introduced;
- [ ] no JVM-local correctness lock is introduced;
- [ ] no new root module is introduced without a demonstrated requirement;
- [ ] Spring Modulith verification remains green.

### Privacy / observability

- [ ] raw key and fingerprint are absent from logs;
- [ ] raw key and fingerprint are absent from metric labels;
- [ ] only bounded idempotency outcome values exist;
- [ ] cross-Tenant replay cannot disclose another Tenant's result;
- [ ] technical failures remain sanitized.

### Regression / governance

- [ ] existing Orders/Catalog/Inventory/Tenants/Users/Security behavior remains
      green;
- [ ] `git diff --check` passes;
- [ ] `.\mvnw.cmd clean verify` passes;
- [ ] multi-replica acceptance remains green;
- [ ] independent final review reports no unresolved correctness blocker;
- [ ] `branch-policy`, `ci-build` and `platform-validation` pass on final PR HEAD.

Only after the full verification evidence exists may ADR-0010 be promoted from
`DESIGNED` to `TESTED`.

## Explicitly deferred

- automatic retry framework;
- transactional outbox;
- Kafka/RabbitMQ;
- webhook delivery;
- Order cancellation/release;
- Catalog/Inventory administrative APIs;
- fine-grained authorization/RBAC/ReBAC;
- Redis/distributed cache;
- cross-operation generic idempotency module;
- key expiry/cleanup;
- exactly-once network delivery claims.

## Follow-up

After OH-012, the S1 flow has durable protection against duplicate external Order
creation.

The next architectural choice should again be triggered by a concrete exposed
problem rather than technology sequencing.

Candidates already recorded in the roadmap include authorization-backed
Catalog/Inventory administration and a transactional outbox once a real
asynchronous consumer exists.

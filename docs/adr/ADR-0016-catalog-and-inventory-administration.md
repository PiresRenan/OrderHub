# ADR-0016 — Catalog and Inventory Administration

Status: DESIGNED

## Authority and problem

OH-018 is governed by issue [#36](https://github.com/PiresRenan/OrderHub/issues/36)
and the S4 roadmap. Discovery on 2026-09-07 proved clean local/remote
`pre-release@35c389197d0ea73ae0182fda9f502425fb67eb91`, tree
`df83f6e8fa6fed510bd669257c485047e134feb6`. The feature branch starts exclusively
from that integrated OH-017 baseline. Accepted migration authority ends at V32,
with historical V19/V20 gaps preserved. ADR-0009 remains TESTED and authoritative.

Existing Catalog models, repositories, Category hierarchy guard and Orderability
are reusable. Existing Inventory positions/policy/commitments already protect
Order allocation. Missing capabilities are governed application commands, bounded
reads, HTTP, administrative evidence and retry safety. Exposing current snapshot
upserts would introduce lost updates; treating receipt as an ordinary update would
double stock after an ambiguous successful response.

Discovery also found that the Tenant authorization kernel and workforce ceiling
resolver exist without production composition for a trusted Staff action. Roles
or a JWT alone cannot replace the current organizational ceiling.

## Decisions and alternatives

### Current Staff authority before sensitive lookup

Workforce owns a narrow Staff authorization facade taking internal User/Tenant IDs
and an existing atomic PermissionCode. It resolves its own durable Staff,
placement and position and delegates to the existing authorization kernel.
The ceiling must be resolved inside the kernel's coherent REPEATABLE_READ snapshot,
alongside roles/overrides; a framework-neutral envelope source permits that without
an authorization -> workforce dependency. The legacy kernel contract may retain
its documented fail-closed behavior, while this administration entry must expose
technical failure distinctly from policy denial.

Rejected: controller-supplied envelopes, JWT permissions, upper-scope grant
inheritance, a second engine and fractured ceiling/role snapshots. Validate with
denied-before-lookup tests, real durable grants, inactive/missing Staff, distinct
permissions and a race where neither the old nor new coherent state authorizes.

### Owner-local commands and HTTP

Catalog/Inventory retain application orchestration, persistence and thin HTTP
adapters. OH-017 administration remains the control-plane adapter. HTTP receives
`security::api` TrustedActorContext through the existing trusted-context resolver;
Tenant suspension continues to be enforced there. Application authorization comes
before sensitive target lookup or movement replay.

Use CATALOG_VIEW, CATALOG_MANAGE, CATALOG_PRICE_MANAGE, INVENTORY_VIEW,
INVENTORY_RECEIVE, INVENTORY_ADJUST and INVENTORY_POLICY_MANAGE independently.
CUSTOMER, Platform and Organization authority do not confer Tenant Staff authority.
Absent and foreign targets have equivalent responses after authorization.

Before: Orders -> Catalog orderability + Inventory commitment; workforce ->
authorization policy model; security -> users/tenants.

After: Catalog/Inventory adapters -> security api; Catalog/Inventory -> workforce
Staff authorization -> authorization Tenant kernel; Inventory identity adapter ->
Catalog identity. No reverse Catalog -> Inventory edge, new generic administration
module or cross-module SQL/FK is introduced. Prove named interfaces and acyclicity.

### Explicit Catalog semantics and stale writers

Create Product/Variant as DRAFT. Keep IDs and Variant parent immutable. Separate
metadata, Category assignment, lifecycle, Category hierarchy and base-price commands
so stale metadata cannot restore lifecycle. Preserve existing domain invariants.
Product activation stabilizes an ACTIVE same-Product Variant witness before the
Product row, preserving ADR-0009 lock order. Category commands acquire the existing
Tenant hierarchy guard before lookup/precondition/ancestry validation/save/audit.

Resource-local revisions with expectedRevision protect noncommutative Catalog
writes; zero denotes absence where creation-by-key is supported. Revisions belong
to application snapshots/persistence, without adding a global optimistic-lock
framework. The necessary schema state is introduced only after executable RED.
Snapshot hashes were considered but would complicate canonicalization, ABA and
large association snapshots. Last-writer-wins is rejected for stale administration.

Money remains explicit ISO currency plus exact non-negative long minor units.
Read only bounded administrative details/lists with deterministic ordering,
resource-specific cursor/limit semantics (maximum 100), no full-catalog response.
Audit captures action, actor, Tenant/resource, revision/meaningful state changes,
server time and correlation without copying arbitrary payloads.

Validate lifecycle versus real Order FOR SHARE checks, stale metadata/lifecycle,
price concurrency, Category inverse reparenting, independent Tenant guards,
assignment isolation, monetary precision and audit rollback.

### Movement is the durable retry outcome

RECEIPT increments physical stock by a strictly positive delta. ADJUSTMENT applies
a nonzero signed correction with a bounded reason. Neither changes committed or
backordered demand. Conditional arithmetic preserves onHand >= committed >= 0 and
long bounds. Receipt does not automatically allocate existing backorders.

A client operation UUID, unique within the trusted Tenant, identifies an immutable
Inventory movement. Canonical versioned comparison binds actor, action, Variant,
delta and reason. Replay after current authorization returns the original stable
movement; identity with a different command/actor conflicts. PostgreSQL uniqueness
coordinates concurrent acquisition. Movement insertion and stock mutation commit
together; a failure leaves neither a successful retry outcome nor a stock effect.
The movement is sufficient operational audit evidence, so no duplicate audit row
or generic PROCESSING/COMPLETED framework is introduced.

A first receipt may create an all-zero Position and apply its delta in that same
transaction after Catalog proves exact Tenant/Variant identity. A separate narrow
Catalog identity interface accepts legitimate DRAFT/INACTIVE identity without
granting Order eligibility. Inventory owns its validation output port/adapter.
Adjustment requires existing state. Missing policy is never invented by receipt.

Rejected: set-quantity primitive, JVM idempotency, automatic retries without durable
identity, event sourcing, a generic ledger and reuse of Order persistence internals.
Validate duplicate/concurrent/lost-response replay, changed payload, cross-Tenant
identity, initial position races, audit failure and technical rollback on PostgreSQL.

### Desired policy state and Orders concurrency

Safety stock is non-negative and may exceed physical availability. Policy remains
DENY/ALLOW_BACKORDER. Desired-state commands compare an expected current value;
already-desired is a no-op, incompatible stale state conflicts, and a successful
change atomically appends before/after evidence. ABA is accepted for this explicit
value-based contract; it does not claim revision-history protection. Existing
commitments are never retroactively rewritten.

The current unlocked policy read becomes a material hazard once policy is mutable.
Prove it with RED before introducing a shared policy lock held through Order
commitment. Global resource order remains Variant -> Product -> Policy -> Position;
workflows using a subset preserve that ordering. Use bounded transactions and real
row/unique/conditional SQL coordination, not process-local locking or global
SERIALIZABLE. Prove receipt/adjustment/safety/policy versus Orders and exact arithmetic.

### HTTP, failure, privacy and persistence

Use bounded DTOs and owner-local `/catalog/...` and `/inventory/...` commands.
Document exact routes with implementation. RFC 9457-style problems have constant
sanitized details/codes; framework 406/415 and trusted-context 401/403 retain their
status. Technical failures remain technical, fail closed and disclose no SQL,
raw exceptions, request bodies, JWTs or authority internals.

Audit/movement evidence is owner-local, append-only and in the authoritative
transaction, never an independent REQUIRES_NEW audit. PostgreSQL enforces critical
quantity/classification/identity constraints and append-only mutation protections.
Existing migrations are immutable; no new migration precedes an executable RED;
published migrations receive only forward corrections. Prove empty -> latest,
V32 -> latest and accepted-file integrity.

Internal actor IDs serve accountability; bounded reason/correlation fields do not
carry arbitrary PII. No business identifiers become metric labels. Existing
low-cardinality telemetry suffices initially. New analytical publication has no
concrete consumer and is deferred. Movement/retry/audit retention is governed future
housekeeping work; this slice has no automatic expiry, purger or replay deletion.

## Execution and acceptance

Every structural slice records problem, evidence/hypothesis, decision, alternatives,
impact and executable validation. Execute RED -> minimal GREEN -> inspect -> justified
refactor -> relevant regression -> hostile self-review -> clean checkpoint. Classify
harness/fixture failures honestly. Do not count previously passing tests as RED.

Initial slices: Staff/kernel composition; Product/Variant and evidence; Category and
pricing; Inventory movement/retry and stock arithmetic; desired policies; HTTP and
real JWT acceptance; migration/concurrency/Modulith/full regression and GitHub review.

Each substantial published checkpoint records SHA, RED/GREEN, test counts,
migrations, concurrency, security/privacy and baseline in issue #36. Open a nonempty
PR to pre-release after green substantive foundation. Request GitHub Codex review
at material persistence/concurrency, HTTP/security and final exact HEAD. Reproduce
real findings with regression RED, fix minimally, revalidate and resolve with evidence.
Review-capacity failure must be recorded explicitly, never represented as approval.

Promotion requires full scoped adversarial acceptance, zero failures/errors, no
hidden skips, diff hygiene, all concurrency/migration tests, Modulith verification,
full Maven Wrapper clean verify, accepted migration integrity and complete diff
review. Exact-HEAD Branch Policy/CI/Platform CI and material review resolution are
mandatory. Only then promote this ADR to TESTED and roadmap to COMPLETE, revalidate
the final governance HEAD, squash-merge with expected-head protection, prove
integrated parent/tree identity, close issue and remove the feature branch.

## Evidence ledger

- Discovery complete; issue #36 created before implementation.
- Baseline Maven Wrapper clean verify passed against the exact integrated authority:
  1,154 tests, zero failures/errors/skips, on 2026-09-07 (5m46s).
- Authorization, Catalog and Inventory foundations are published in PR #37. V33–V35
  establish revisions and owner-local evidence. Category/price/assignment/discovery
  regression on integrated checkpoint d4a31d9 passed 230 tests, zero failures/errors/skips.
- GitHub review identified a real Catalog TRUNCATE gap in V33. An executable PostgreSQL
  RED proved it; V35 adds the statement trigger forward-only. Accepted migrations remain
  unchanged. A forced concurrent writer also proved inconsistent revision/hydration;
  Product/Variant detail now stabilizes root reads, and Category detail uses one snapshot.
- Owner-local HTTP composition and real JWT/Staff acceptance passed 26 targeted tests
  including Modulith. Full clean verify passed 1,233 tests, zero failures/errors/skips,
  on 2026-09-08. Final migration/Order-administration race acceptance and exact-HEAD
  review/release gates remain pending; this is a checkpoint, not final acceptance.
- The concrete API, input limits, replay/precondition behavior and errors are documented
  in [the HTTP contract](../catalog-inventory-administration-http.md).
- This ADR remains DESIGNED until the complete acceptance gate is proved.

## Explicitly deferred

Warehouse/location, transfer, procurement, full PIM, advanced pricing/promotions/tax,
search/recommendations, media pipeline, arbitrary attribute schemas, bulk import/export,
frontend, authorization redesign, generic audit/workflow/CQRS/event sourcing,
brokers/caches/new services/databases, analytics without a consumer and housekeeping.

## Primary references

Repository: ROADMAP; ADR-0003; ADR-0009 TESTED; ADR-0010; ADR-0011/0012; ADR-0015;
accepted migrations and executable tests. Material external semantics checked on
2026-09-07:

- [PostgreSQL 18 isolation](https://www.postgresql.org/docs/18/transaction-iso.html)
- [PostgreSQL 18 locking](https://www.postgresql.org/docs/18/explicit-locking.html)
- [RFC 9110](https://www.rfc-editor.org/rfc/rfc9110.html)
- [RFC 9457](https://www.rfc-editor.org/rfc/rfc9457.html)

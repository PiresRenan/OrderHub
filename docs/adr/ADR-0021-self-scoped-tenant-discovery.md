# ADR-0021 — Authenticated self-scoped Tenant discovery

Status: DESIGNED — implementation and exact-candidate qualification pending.

Numbering note: ADR-0021 and migration V45 are provisional on the isolated OH-023
branch. Before post-v1 integration, the next available ADR and migration numbers
are re-derived from the released baseline. If OH-022 has used either number, this
work is renumbered. Integrated authority is never overwritten, and out-of-order
migration is never enabled.

Task: OH-023, [Issue #52](https://github.com/PiresRenan/OrderHub/issues/52).

Classification: **POST-v1**. This capability is not part of the OH-022 / v1.0.0
scope. The frozen v1 contract (60 operations, ADR-0020) is unchanged. OH-023 is
integrated into `pre-release` only after v1.0.0 is promoted, tagged and published,
and only after the candidate is synchronized with the post-v1 baseline and fully
requalified.

## Problem

Orders, Catalog and Inventory require an `X-Tenant-Id` selector. A client can
only obtain a valid selector out of band. There is no authenticated operation
that lets the current internal User discover the Tenant contexts it may
currently select. Clients such as the OrderHub Web BFF must not fill that gap by
reading OrderHub persistence, trusting provider claims, or inventing a Tenant
list of their own (BFF ADR-0009 / D023).

## Current capability

- `ResolveTrustedTenantContextService` establishes trusted Tenant context for
  every Tenant-scoped request from the current state: Users answers whether the
  exact membership is operationally ACTIVE, and Tenants answers whether the
  Tenant is operationally ACTIVE.
- `GET /organizations/{organizationId}/tenants` is an Organization
  administration read. It requires administrative grants and is not a
  self-scoped substitute.
- `GET /identity/external-accounts` is the self-scoped precedent: the actor is
  derived only from the bearer-bound internal User, and the response carries
  `Cache-Control: no-store`.

## Decision

Add one self-scoped read operation:

```http
GET /tenants?limit=<1..100>&afterId=<uuid>
Authorization: Bearer <access-token>
```

### Actor and authority

- The actor is the bearer-bound internal User produced by the existing
  authentication chain: verified bearer -> exact issuer + subject -> internal
  User.
- There is no caller-supplied `userId`. No `X-Tenant-Id` is read. Provider
  claims, groups and scopes are never consulted.
- **Discovery is not authorization.** A returned Tenant grants nothing. Every
  Tenant-scoped business request still establishes `TrustedTenantContext` from
  current OrderHub state. A discovery result is never an authorization lease,
  must never be cached as authority, and must never be copied into a token or a
  BFF session as an authority set.

### Ownership

- Users owns membership eligibility. A new Users input port lists the tenant
  identifiers of the caller's ACTIVE memberships in bounded scan windows.
- Tenants owns Tenant operational state and name. A new Tenants `operational`
  input port reads, in one bounded batch, the ACTIVE Tenant summaries for a
  supplied set of identifiers.
- Security composes the two answers, as it already does for
  `TrustedTenantContext`. No cross-schema SQL join is introduced and no module
  reads a foreign schema.

### Projection

Each item contains only `id` (Tenant UUID) and `name` (the normalized Tenant
name, at most 120 code points). The response does not include membership status,
roles, permissions, Staff/Customer identifiers, issuer, subject, administrative
grants or Organization metadata.

### Bounded scan pagination

```json
{ "items": [ { "id": "<uuid>", "name": "<name>" } ], "nextAfterId": "<uuid-or-null>" }
```

Each request does bounded work:

1. Users reads the caller's ACTIVE memberships with `tenant_id > afterId`
   (exclusive), `ORDER BY tenant_id`, `LIMIT limit + 1`.
2. The scan window is the first `limit` candidates.
3. Tenants reads the window in one batch and keeps only ACTIVE Tenants.
4. `items` are those summaries, in `tenant_id` order.
5. If the Users read returned more than `limit` rows, `nextAfterId` is the
   tenant id of the **last scanned membership candidate**, not the last returned
   Tenant. Otherwise `nextAfterId` is `null`.

Consequences:

- Short pages and **empty pages with a non-null `nextAfterId` are valid**. A
  window whose Tenants are all suspended returns `{"items":[],"nextAfterId":"<last>"}`.
- Clients must not infer the end of the list from `items.size < limit`. The end
  is signaled only by `nextAfterId == null`.
- Filtered rows still advance the cursor, so suspended Tenants are never
  rescanned.
- Pages are not a snapshot, and no total count is provided.
- `limit` defaults to 50 and must be between 1 and 100. A malformed `afterId` or
  an out-of-range `limit` returns the existing 400 Problem Details.
- The cursor is pagination control data. It only identifies a Tenant the caller
  already holds an ACTIVE membership in (at scan time), so it discloses nothing
  outside the caller's own relationship set. No cursor signing or encryption is
  introduced.

Per request, the database work is exactly one Users query and at most one Tenants
query, independent of how many rows are filtered. An empty scan window performs no
Tenants query. The Tenants batch read has no `ORDER BY`; Security rebuilds the page in
the Users scan order (`tenant_id` ascending), so the response order never depends on
the order in which `ANY (...)` returns rows.

### Failure semantics

| Condition | Result |
| --- | --- |
| Missing, malformed or invalid bearer | existing sanitized 401 |
| Verified identity with no internal binding | existing bounded 401 (non-enumerating) |
| Bound User with no eligible Tenant | `200 {"items":[],"nextAfterId":null}` |
| Suspended or terminated membership | omitted (not scanned) |
| Suspended Tenant | omitted (scanned, advances the cursor) |
| Invalid `limit` / `afterId` | existing 400 Problem Details |
| Persistence failure or invalid persisted state | existing sanitized 5xx |

Filtering never produces 5xx. There is no per-Tenant 403/404, and no Tenant is
looked up from caller input, so the operation provides no Tenant-existence
oracle.

### Lifecycle races

A Tenant returned at T0 can be suspended, or its membership suspended or
terminated, at T1. A business request at T2 with `X-Tenant-Id` set to that Tenant
is denied by the existing `TrustedTenantContext` semantics. No snapshot or lock
is introduced for discovery.

### Cache policy

Successful responses carry `Cache-Control: no-store`. Tenant eligibility is
lifecycle-sensitive. Performance comes from bounded, indexed reads, not from
caching. No cache infrastructure is introduced.

### Index

The only existing membership index is `UNIQUE (tenant_id, user_id)`, which does
not lead with `user_id`.

Evidence was gathered on PostgreSQL 18.6 (the pinned test image) with the exact
V3/V4/V36 table definition, synthetic data and `EXPLAIN (ANALYZE, BUFFERS)`:

- about 1.0M memberships across 200,001 Users and 50,000 Tenants;
- about 5 memberships per typical User, plus one User with 5,000;
- status mix of 85% ACTIVE, 10% SUSPENDED and 5% TERMINATED.

| Plan | Typical User, first window (`LIMIT 51`) | Heavy User, later window (`LIMIT 101`) |
| --- | --- | --- |
| Existing index only | Parallel Seq Scan over all rows: 8,545 buffers, 18–25 ms (custom and generic plans) | Index scan on the unique index, filtered: 251 buffers, 2.2 ms |
| A: `(user_id, status, tenant_id)` | Index Only Scan: 4 buffers, 0.03 ms (custom **and** generic plans) | Index Only Scan: 5 buffers, 0.07 ms |
| B: partial `(user_id, tenant_id) WHERE status = 'ACTIVE'` | Custom plan: Index Only Scan, 4 buffers. **Generic plan: Parallel Seq Scan, 8,545 buffers** | Index Only Scan: 5 buffers |

Without an index, every discovery request scans the whole membership table, so
its cost grows with the total number of memberships in the system rather than
with the caller's own. Candidate B matches only when `status` is the literal
`'ACTIVE'`. The adapter binds `status` as a parameter, and the JDBC driver
switches to server-prepared generic plans after repeated executions, so B then
falls back to a full scan. Candidate A (58 MB on this dataset; B is 41 MB)
serves both plan kinds.

These figures are local observations used only to justify the index and the
bounded query shape. They are not a latency SLA or a production capacity claim.

Reproduction (synthetic data, disposable container, no published secrets):

```sql
-- schema: users.users(id pk); users.tenant_memberships(user_id, tenant_id,
-- status) with UNIQUE (tenant_id, user_id), FK to users.users and the V36 CHECK
INSERT INTO users.users SELECT gen_random_uuid() FROM generate_series(1, 200000);
-- per User: 5 random Tenants out of 50,000; status ACTIVE < 0.85 <= SUSPENDED < 0.95 <= TERMINATED
-- one additional User with 5,000 ACTIVE memberships; then VACUUM ANALYZE
PREPARE first_w(uuid, text, int) AS
  SELECT tenant_id FROM users.tenant_memberships
  WHERE user_id = $1 AND status = $2 ORDER BY tenant_id LIMIT $3;
EXPLAIN (ANALYZE, BUFFERS) EXECUTE first_w('<typical-user>', 'ACTIVE', 51);
SET plan_cache_mode = force_generic_plan;  -- repeat for the generic plan
```

**Decision:** `V45__index_tenant_memberships_by_user_status.sql` creates
`ix_tenant_memberships_user_status_tenant ON users.tenant_memberships (user_id,
status, tenant_id)`. Accepted migrations V1–V44 and B44 are unchanged. The index
is additive. The build takes a write lock on `users.tenant_memberships` for its
duration, which is acceptable for a table of this shape but must be scheduled
consciously on large retained datasets.

### OpenAPI

The operation is generated from the MVC handler, with authentication, query
parameters, page schema, 200/400/401/5xx semantics and the `Cache-Control`
header. The generated artifact on the OH-023 branch is a post-v1 candidate only.

## Alternatives rejected

- **Server-side refill loop with a round cap, then 5xx.** Rejected. Many filtered
  rows are legitimate lifecycle state, not a technical failure, and request cost
  would grow unpredictably with the number of filtered rows.
- **Cross-schema join between `users.tenant_memberships` and `tenants.tenants`.**
  Rejected. It breaks schema ownership (ADR-0006). The two-read composition is
  already bounded.
- **Bare array paged by the last returned Tenant.** Rejected. With filtering, a
  short page cannot be distinguished from the end of the list.
- **Tenant list in token or BFF session claims.** Rejected. It would be stale
  authority derived outside current OrderHub state.
- **Administrative listing reused for self-discovery.** Rejected. It has a
  different actor and authority model.
- **Opaque or signed cursor.** Not needed. The cursor reveals nothing outside the
  caller's own relationships.

## Security and privacy impact

- The operation is self-scoped only. It creates no new authority path and no
  Tenant-existence oracle.
- Problem Details and logs contain no token, subject or issuer.
- No high-cardinality telemetry labels are introduced.

## Executable validation

Validation uses real PostgreSQL and a real JWT through the production security
composition:

- positive cases: single and multiple memberships, empty set;
- lifecycle filtering: suspended/terminated membership, suspended Tenant;
- isolation: foreign-Tenant non-leakage, provider claims ignored, no role or
  permission data;
- errors: 401 for invalid or unbound identity, 400 for invalid parameters,
  sanitized 5xx for persistence failure;
- pagination: limit bounds, deterministic cursor, and all-filtered window with
  continuation;
- lifecycle after discovery: membership suspension and Tenant suspension deny
  subsequent Tenant-scoped requests;
- concurrency: concurrent disjoint Users with zero cross-contamination;
- selector: changing only `X-Tenant-Id` does not cross a Tenant boundary;
- query bound: at most two statements per request;
- contract: an OpenAPI contract test.

## Rollback

Revert the OH-023 squash commit. A V45 index, if added, is additive and harmless.
Any correction to it is forward-only.

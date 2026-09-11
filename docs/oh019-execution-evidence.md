# OH-019 execution evidence — in progress

This is an intermediate evidence ledger, not OH-019 completion or PR readiness.
Issue #38 remains the product authority. ADR-0017 remains DESIGNED.

## Starting authority

- Branch: `feat/OH-019-tenant-identity-provisioning`.
- Local, origin-tracking and remote HEAD:
  `edfc7a8eddde48baa1c8a118f7341a50de60c85f`.
- Tree: `c0c456aab535a0903fdeb3ec84997c540f82ac09`.
- Parent: `6811e4c01df32fccad9307bcd073b40805e59056`.
- Initial worktree/index/stash were empty.
- `origin/pre-release`: `9e87d15e98db88f95f5829f6e8f57a4aadfb4b80`.
- Published migrations end at V37; V19/V20 remain historical gaps.

## Staff materialization

Discovery found workforce domain/placement persistence and position mutation,
but no narrow production write for creating Staff plus its required placement.
The new owner-local persistence port implements an exact desired state:

- absent Staff: create ACTIVE Staff and its placement together;
- ACTIVE Staff with the exact placement: return the same Staff identity;
- INACTIVE Staff, missing placement or different placement: conflict;
- cross-Tenant placement references: rejected by the existing V13 constraints.

There is no implicit reactivation, placement repair or overwriting of an existing
relationship. The adapter requires a transaction bound to its own DataSource and
does not create or commit an independent transaction. No migration was needed.

Evidence:

- Initial executable RED: six failed tests because materialization was absent.
- Initial GREEN: six PostgreSQL tests passed.
- Permanent suite: nine tests, including 32 concurrent exact-creation rounds.
- Adversarial RED: a transaction on a different DataSource admitted independent
  autocommit writes. The adapter now rejects this before writing. Its regression
  passes with zero durable rows.
- Fixture compilation and image-compatibility failures were repaired before the
  initial product RED and are not counted as product evidence.

## Consumption application foundation

The new coordinator executes consume -> current-authority collaborator -> Users
resolve-or-create -> ensure ACTIVE membership -> Staff materialization -> required
completion collaborator through one workforce transaction executor. It decodes
only the canonical 32-byte Base64URL credential and passes only its SHA-256 digest
to intent persistence. Unknown or unusable proofs produce a bounded rejection.

The first propagation tests used a synthetic authorization collaborator. Those
tests do **not** certify production delegation or cold-start policy. Subsequent
work below composes the normal consumption flow with real owner capabilities;
HTTP/bootstrap and cold start are still pending.

Evidence:

- Initial application RED: five tests failed because orchestration was absent.
- Five application tests pass, covering proof decoding, operation ordering,
  non-operational membership and early authority failure.
- Spring Modulith initially rejected the new dependency. The only admitted edge
  is `workforce -> users::api`; module verification now passes.
- Nine real PostgreSQL transaction tests pass, including:
  - downstream failure rolls back intent/User/binding/membership/Staff;
  - successful retry after rollback;
  - an outer rollback undoes an inner successful return;
  - Users failure after inner creation leaves no speculative orphan;
  - TERMINATED membership and INACTIVE Staff preserve historical state;
  - 32 consume/consume rounds with one terminal effect;
  - 32 consume/cancel rounds with one terminal effect;
  - a competing external identity scope is observed waiting on a PostgreSQL
    advisory lock until outer commit, and separately until outer rollback.

Latest completed combined regression:

```text
mvnw.cmd -B -Dtest=PostgreSqlStaffMaterializationTest,StaffProvisioningConsumptionServiceTest,StaffProvisioningConsumptionTransactionTest,OrderHubModularityTests test
27 tests / 0 failures / 0 errors / 0 skipped
```

The starting published primitives also passed a separate 40-test regression.
No full `clean verify` of the changed candidate has completed yet.

## Normal production consumption and authority

- V38 was added only after a three-test PostgreSQL RED proved missing
  `workforce.provisioning_events` state. Its GREEN proves bounded result shape,
  one issuance mode, and UPDATE/DELETE/TRUNCATE protection.
- The production evidence writer joins the same DataSource transaction. The
  nine-test consumption propagation suite now uses this real evidence writer.
- The seven-test authorization service RED established the missing concrete
  initial-role boundary. Its GREEN plus the existing five RoleDelegationPolicy
  tests requires independent member-management/role-assignment permissions,
  protected/self-assignment controls and rejection of overbroad target ceilings.
- V1 delegation is bounded by currently held role-definition envelopes,
  intersected with the current workforce ceiling and effective permissions.
  Actor-specific ALLOW overrides do not enlarge the delegation envelope. This
  keeps effective permission and delegation sources distinct and deliberately
  admits no new delegation-administration product.
- V39 follows two executable PostgreSQL REDs: missing role-provisioning audit,
  and an existing assignment DELETE passing an acquired provisioning authority
  scope. Assignment/override writers now share per-User/Tenant transaction locks.
  Role metadata receives compatible shared table locks during provisioning;
  metadata edits wait, while independent provisioning remains compatible.
- The real role writer proves assignment/audit outer rollback and successful
  retry. Policy tests and persistence tests passed together: 15 / 0 / 0 / 0.
- Workforce resolves ACTIVE Staff and same-Tenant placement facts under row
  locks, including position permission membership. Modulith admits only
  `authorization::staff-provisioning`, `users::api` and `tenants::operational`
  for the newly required owner contracts.
- Normal completion revalidates issuer membership and Tenant operational state,
  requires manager authority before target lookup, validates placement/role
  delegation, and appends successful workforce attribution after Authorization.
- `ConsumeStaffProvisioningUseCase` is now composed in production, using the
  existing transaction manager and a 15-second REQUIRED transaction boundary.
  Runtime composition, full application context and Modulith passed: 8 / 0 / 0 / 0.
- Five initial full-runtime PostgreSQL tests passed. They prove successful
  audited consumption/replay rejection, prior issuer revocation, overbroad-role
  rejection, real workforce-audit INSERT failure rolling back role/audit and
  identity relationships, and real authorization-audit INSERT failure rollback.

### Consumption time authority findings

- The production race suite exposed both consumers rejecting one newly issued
  proof. Added bounded diagnostics captured application time
  `2026-09-09T17:24:24.269728400-03:00` preceding database `created_at`
  `2026-09-09T17:24:24.270049-03:00`. The two independent clocks can therefore
  reject a committed, immediately consumed proof as not yet valid. Production
  consumption now obtains time from PostgreSQL through a composition-owned
  `Clock`; it reads no intent and retains the atomic terminal UPDATE RETURNING.
- A separate executable RED held the intent row, observed the consumer waiting
  in `pg_locks` before expiry, then released the row only after database expiry.
  The old implementation succeeded using its pre-wait timestamp. V40 adds a
  BEFORE ROW transition guard using `clock_timestamp()` after row acquisition;
  rejection returns no row and prevents identity/relationship side effects.
  This preserves V37 and the original repository mutation contract.
- The earlier intermittent failure is recorded in `oh019-production-races.log`;
  its clock diagnosis is in `oh019-clock-diagnostic.log`. The deterministic
  expiry RED is in `oh019-expiry-wait-red.log`. These are local temporary logs,
  not the final qualification evidence package.

V38-V40 are introduced by the normal Staff checkpoint and become immutable upon
publication. V1-V37 have not been modified.

### Intermediate complete regression

`mvnw clean verify` completed on 2026-09-09 at 17:34:07 America/Sao_Paulo:
**1,401 tests / 0 failures / 0 errors / 0 skipped**, BUILD SUCCESS, 8:03 minutes.
The captured log is `oh019-full-verify.log` in the local temporary directory.
This run covers production consumption, the PostgreSQL clock and V40, including
the original published regression suite. It does not include the administration
and completion-upgrade tests prepared after that run compiled its test snapshot.
Those tests require their own subsequent qualification; this is not final
OH-019 qualification or PR readiness.

### Normal issuance and cancellation

- Five runtime REDs established the missing authorized administration service;
  their GREEN preserves manager-first target access, complete placement/role
  validation, digest-only primitive issuance, replay without secret recovery,
  Tenant/intent cancellation and one event per applied transition.
- A composition RED found no administration bean; production composition and
  the five policy tests subsequently passed together (8 / 0 / 0 / 0).
- A deterministic PostgreSQL RED proved an intent/issuer lock inversion between
  cancellation and consumption (`oh019-cancellation-lock-order-red.log`).
  Cancellation now uses the existing read-only current-authority boundary for
  its initial access gate, then acquires the intent before locking/revalidating
  mutation authority. The existing read boundary remains read-only; every
  cancellation/evidence mutation joins the authoritative REQUIRED transaction.
- The reproducer passes after correction. A separate real PostgreSQL test
  revokes the issuer's role while cancellation waits: the later locked check
  rejects it, rolls back cancelled_at and writes no cancellation evidence.
- Real issuance/cancellation audit INSERT failures roll back the associated
  proof mutation and admit a subsequent retry. The production suite plus unit
  policy and V37-upgrade test passed (19 / 0 / 0 / 0) in
  `oh019-administration-audit.log`.
- A second deterministic RED (`oh019-issuance-replay-lock-order.log`) proved
  issuance replay waiting in the uniqueness check while consumption held the
  intent and waited for issuer authority. The published creation adapter now
  recognizes already committed operation/fingerprint identities with a
  nonlocking read; concurrent first creation retains INSERT ON CONFLICT and
  its existing post-conflict recognition. This evidence justifies the bounded
  adapter change without changing frozen issuance semantics or migrations.
  Consumption still performs no pre-read and retains UPDATE RETURNING.
- The first verification used the previously compiled test's mandatory-wait
  assertion, which timed out because replay no longer waits. The corrected
  synchronization admits either replay completion or an observed database wait
  before resuming consumption; both outcomes must complete without exceptions
  and return the exact replay identity. The freshly compiled regression passed
  25 / 0 / 0 / 0 (`oh019-issuance-replay-lock-order-green-current.log`).
  These additions postdate the 1,401-test complete regression.

### Normal Staff checkpoint qualification

The complete fresh `mvnw clean verify` finished on 2026-09-09 at 23:35:30
America/Sao_Paulo: **1,413 tests / 0 failures / 0 errors / 0 skipped**, BUILD
SUCCESS, 8:18 minutes (`oh019-normal-staff-clean-verify.log`). This run includes
the authorized administration contract, V37 upgrade preservation, cancellation
revocation/rollback, both lock-order regressions, all newly added tests and the
published suite. `git diff --check` and the staged equivalent passed. Published
V1-V37 migrations remain byte-identical to the starting authority.

## Next required work

### Explicit first-Staff ceremony, in progress

The normal Staff checkpoint was published as
`ee10b3f47cb9b901f84d4ec57728f25f670ecf4c`, tree
`4562d1955564891aae4b0b72e0cd8a318c0a7c32`. The remote feature branch matched it
and the worktree was clean immediately afterward; pre-release stayed unchanged.

- Three full-runtime REDs established the missing first-Staff contract against
  a genuinely empty Tenant, an actor without Platform authority and historical
  INACTIVE Staff. Existing Workforce/Authorization tables suffice; no migration
  beyond published V40 is introduced by this ceremony.
- Merely accepting preconfigured department/position/role selectors would leave
  a newly created Tenant unusable. The explicit ceremony therefore prepares
  owner-local initial governance placement and creates the Tenant-owned
  `INITIAL_TENANT_GOVERNANCE_V1` role only when assigning the first Staff.
  Its 17 STAFF permissions are an explicit versioned set, not an enumeration of
  future permissions, and include no Platform or Customer permissions. Existing
  metadata is validated, never silently overwritten or clipped.
- `PLATFORM_TENANTS_MANAGE` is checked before Tenant/target access. Its real
  grant row is held FOR SHARE through the outer transaction. No Staff actor is
  fabricated to pass normal RoleDelegationPolicy.
- The existing workforce governance Tenant lock serializes first-Staff attempts.
  Any Staff row, including INACTIVE history, or prior cold-start consumption
  evidence closes this path. Immutable COLD_START_ISSUED attribution selects the
  explicit mode; a failed normal authorization check never falls back to it.
- Platform cancellation is limited to cold-start proofs before any Staff exists.
  After bootstrap, normal Tenant authority can clean up remaining proofs;
  Platform cannot continue managing private Staff through this contract.
- The initial composed regression passed 24 / 0 / 0 / 0, then the expanded owner
  transaction/normal-provisioning/modularity regression passed 31 / 0 / 0 / 0.
  The final focused cold-start suite passed 12 / 0 / 0 / 0, including 32 rounds
  of competing first-Staff proofs and 32 consumption/cancellation races.
- Real audit INSERT failures roll back prepared metadata/issuance or
  role creation/User/membership/Staff/consumption as applicable. A real grant
  revoker waits for the outer transaction; a revoked grant prevents later
  consumption. Altered placement ceilings deny without repair.
- These tests use trusted synthetic issuer/subject facts. They do not claim
  cryptographic JWT/bootstrap-adapter qualification, which remains pending.

- Full `clean verify` completed successfully on 2026-09-10 at 00:10:24 -03:00:
  1,425 tests / 0 failures / 0 errors / 0 skipped (7:48 minutes).
  Log: `%TEMP%/oh019-cold-start-clean-verify.log`. Published migrations V1-V40
  remain unchanged and `git diff --check` passes.

### Remaining capabilities

1. First-Staff checkpoint published as `7bbfe7664ab9c213f5a6f3c7ec28eb4ad54288b2`
   (tree `8dab044bcfa1082d76b7c188df51462ae106bca4`); remote confirmed.
2. Add the verified bootstrap/HTTP adapters and their adversarial qualification.
3. Complete Customer proof/linking; external link/unlink/migration; operational
   membership/trusted-context lifecycle; minimal real-JWT HTTP adapters.
4. Complete adversarial review, migrations, full qualification, governance,
   PR/CI and both exact-HEAD GitHub Codex reviews.

This normal Staff checkpoint does not claim OH-019 completion. No PR has been
created, no GitHub review requested, and no merge or issue closure performed by
this run.

### Customer proof foundation, in progress

- Re-read ADR-0013 and OH-015 issue #30, including its coordination and final
  integration comments. Exact `(tenantId, customerId, userId)` cardinality is
  preserved; Customer UUID knowledge remains only a selector, never proof.
- Three PostgreSQL RED tests established the missing Customer proof schema.
  V41 adds a same-Customer/Tenant foreign key, digest-only credential column,
  bounded lifetime and operation/digest uniqueness. V1-V40 are unchanged.
- The three new checks passed. The original V17 schema contract initially
  rejected the added table because it enumerated the entire schema. Its exact
  assertions now target the original two tables on the latest migrated schema;
  new proof state has separate checks. Combined regression: 4 tests / 0 failures
  / 0 errors / 0 skipped, completed 2026-09-10 06:46:31 -03:00.
- Logs: `%TEMP%/oh019-customer-proof-schema-red.log`,
  `%TEMP%/oh019-customer-proof-schema-green.log` (historical enumeration failure),
  and `%TEMP%/oh019-customer-proof-schema-qualified.log`.
- The executable runtime RED failed for the absent Customer linking API.
  Production composition now exposes the narrow `account-linking` contract:
  issue, consume and cancel. It accepts internal authenticated User IDs, not
  provider selectors; cryptographic JWT/HTTP qualification remains pending.
- Issuance requires current active Tenant, operational issuer membership and
  workforce-bounded `TENANT_MEMBERS_MANAGE` before Customer lookup. This is an
  explicit Tenant account-administration permission, not Customer ownership
  inferred from being Staff elsewhere. Consumption revalidates its original
  issuer; cancellation revalidates after a possible proof-row wait.
- Authority uses the existing Staff read-decision contract: it is a current,
  point-in-time decision, not a promise to cancel already-started operations
  when later authority revocation commits.
- Secrets have 256 random bits, canonical unpadded Base64URL encoding, a
  15-minute issued lifetime and SHA-256 digest-only storage. Issued result
  `toString()` redacts its credential. Operation replay returns only proof ID;
  incompatible Customer/issuer reuse denies. A lost successful issuance
  response requires cancellation and a new operation, never secret recovery.
- Consumption selects the Customer only from the proof, not another request
  Customer ID. It joins one REQUIRED physical transaction for terminal proof
  transition, Users-owned ensure-active membership, exact Customer binding and
  mandatory owner-local evidence. It neither creates User identity nor grants
  Staff/roles; non-operational membership is never implicitly reactivated.
- PostgreSQL handles one-winner proof transitions. A row trigger rechecks the
  deadline after lock acquisition. Independent proofs for the same exact tuple
  converge without imposing global cardinality or removing existing owners.
  Evidence is append-only and contains only internal attribution identifiers.
- The owner adapter rejects autocommit and transactions bound to a different
  DataSource. Real audit INSERT rejection rolls back issuance, cancellation,
  or consumption/membership/binding and permits the specified retry.
- Initial runtime/schema/modularity regression: 9 / 0 / 0 / 0. The expanded
  suite exposed two test-fixture errors while adding artificial audit CHECKs
  over prior rows; `NOT VALID` now leaves prior evidence intact while enforcing
  rejection for new writes. These were fixture errors, not product failures.
- Final targeted/broader regression passed 50 / 0 / 0 / 0, including Customer
  upgrade/schema, modularity, normal Staff and first-Staff suites. Four Customer
  concurrency cases each ran 32 rounds: same-proof consumers, consume/cancel,
  duplicate issuance, and independent proofs for the same User. Expiry during
  an observed PostgreSQL lock wait leaves proof, binding and membership intact.
- Upgrade from V40 preserves historical Customer bindings and all accepted
  migration checksums. The first full `clean verify` ran 1,444 tests with zero
  assertion failures and seven setup errors in two historical fixture cleanup
  methods: they truncated CustomerProfile without the new referencing proof
  table. Both fixture TRUNCATE lists now include that table; production FK and
  append-only evidence protections are preserved. Both focused fixture checks
  passed (3 and 4 tests). Final full `clean verify` then passed 1,444 / 0 / 0 / 0
  in 7:39 minutes, completed 2026-09-10 12:11:24 -03:00. The exact Customer
  manifest was reviewed, `git diff --check` passed and V1-V40 are unchanged.
  Two newly prepared, untracked external-lifecycle test sources belong to the
  next capability; they were neither compiled in this run nor included in this
  checkpoint's manifest.
  Logs: `%TEMP%/oh019-customer-link-runtime-red.log`,
  `%TEMP%/oh019-customer-link-runtime-green.log`,
  `%TEMP%/oh019-customer-link-adversarial.log`,
  `%TEMP%/oh019-customer-link-qualified.log`,
  `%TEMP%/oh019-customer-link-regression.log`,
  `%TEMP%/oh019-customer-link-clean-verify.log`.
  Final passing log: `%TEMP%/oh019-customer-link-clean-verify-final.log`.

### External identity lifecycle, in progress

- Customer checkpoint was committed and pushed normally as
  `d25469ca502728ba6c800bbe92b39e3ecb5d54a7`, tree
  `487f194637e9ef1b5e6a11930700d63436c3cfde`; remote confirmed. V1-V41 are now
  published and unchanged. No PR/review/merge or issue closure has occurred.
- Runtime/schema RED: missing lifecycle API and missing binding lifecycle
  columns; a second schema RED established the absent digest-only proof table.
  V42 adds opaque binding IDs, active state, immutable owner/pair checks,
  one-time link proofs and append-only Users evidence. Existing identities,
  Customer references, suspended membership and accepted checksums survive the
  V41 upgrade without normalization or reassignment.
- Linking preserves one User and joins the published exact-pair advisory lock.
  Unlink retains the binding and owner but removes it from ordinary identity
  resolution. Re-linking requires a new proof and independent identity
  verification. First-sighting of a revoked pair cannot commit a replacement
  User: the retained uniqueness conflict rolls the whole attempt back.
- All lifecycle writes/evidence use one REQUIRED physical transaction. The
  existing User row uses FOR NO KEY UPDATE to serialize link/unlink without
  conflicting with legacy binding INSERT foreign-key KEY SHARE checks. No
  JVM-local correctness lock or cross-module SQL was introduced.
- Last-path removal is denied. The remaining path must be locally active and
  have a currently configured trusted issuer; an active row at a retired
  provider is insufficient. This checks application-controlled usability, not
  remote provider account suspension or availability. No orphan recovery API
  was added.
- A real signed-JWT RED rejected the additional configured provider. Security
  now supports an explicit additional-issuer/JWK list using the same validation
  policy/audience and configured decoder bean. Unknown issuer routing never
  fetches an endpoint selected by the token. Primary trust cannot be shadowed,
  incomplete additional configuration fails startup, and the Users trust port
  reads the same server-owned issuer set. Initial crypto/modularity: 7 / 0 / 0 / 0.
- Initial composed lifecycle run had 14 tests, with two isolated composition
  fixture failures because its synthetic infrastructure omitted the new
  mandatory provider-trust port. The fixture now supplies a non-executing test
  implementation; no optional production fallback was added. Next run: 21 / 0 / 0 / 0.
- Broad Users regression exposed 57 setup errors across seven old TRUNCATE
  fixtures after the new same-owner FK. Their explicit cleanup lists now include
  the mutable link-proof table while keeping evidence append-only. Corrected
  Users/runtime/upgrade/crypto/modularity regression: 88 / 0 / 0 / 0.
- Final composed regression passed 70 / 0 / 0 / 0, including normal Staff,
  first-Staff and Customer. External lifecycle cases include five concurrency
  scenarios, each with 32 rounds: competing owners for a pair, two unlinks,
  same-proof competing identities, linking versus first-sighting, and provider
  migration. Expiry during an observed PostgreSQL lock wait prevents linking.
  Real audit failures roll back issue/cancel/link/unlink and allow valid retries.
- Authentication resolution can see the old active row while unlink is
  uncommitted; subsequent resolution after its commit denies the old identity.
  Historical owner/binding rows remain. This does not retroactively stop a
  previously authenticated in-flight request.
- Application lifecycle tests use synthetic verified facts, not a public raw
  issuer/subject claim API. Real bootstrap/HTTP acceptance and membership
  operational administration remain pending. ADR-0017 remains DESIGNED.
- The first full `clean verify` ran 1458 tests with zero assertion failures and
  four composition errors: three additional isolated fixtures omitted the
  mandatory trust port. Explicit non-executing test collaborators corrected the
  setup; the affected composition/transaction suites passed 12 / 0 / 0 / 0.
  Production trust remains mandatory. The fresh full `clean verify` passed
  **1466 / 0 / 0 / 0**, independently totaled from Surefire XML, in 8:30 at
  2026-09-10 17:15:42 -03:00 (`oh019-external-lifecycle-clean-verify-final.log`).
  The 22-test increase over the published Customer checkpoint is 15 lifecycle,
  four schema, one upgrade and two additional-provider trust cases.
  Logs are in `%TEMP%` with prefixes
  `oh019-external-lifecycle-` (red, schema-red, green, adversarial,
  users-regression, qualified, final-regression, clean-verify) and
  `oh019-additional-provider-trust-` (red, green).

## Operational membership administration

- External lifecycle checkpoint was committed and pushed normally as
  `7b11bea81b4569923260746aab31341c86ac6a85`, tree
  `7176653a7ca4b661ff4e7802abc4bea7f46710a0`; remote confirmed, clean worktree.
  V1-V42 are now published and immutable. Its full qualification is 1466 / 0 / 0 / 0.
- Runtime RED: two cases failed because the administration contract did not
  exist. Schema RED independently proved the missing owner evidence table.
  V43 adds only bounded append-only membership transition evidence.
- Users owns locked state transition and evidence; Workforce coordinates
  current Tenant/member/Staff authority through TENANT_MEMBERS_MANAGE. Staff
  targets must fit actor band and position envelope. Self transitions are
  denied, Customer-only targets need no fabricated Staff, and no role changes.
- Explicit recovery supports only SUSPENDED -> ACTIVE. TERMINATED is terminal;
  desired-state replay returns unchanged without duplicate evidence. Published
  ensure-active semantics still reject non-operational memberships.
- Existing Tenant governance advisory serialization plus post-wait authority
  checks prevent mutual administrator disable. Each of mutual termination and
  recovery-versus-termination passed 32 real PostgreSQL rounds.
- Real evidence-write failures roll back all three actions; retry succeeds.
  Outer rollback preserves membership and evidence. Historical Staff and
  Customer bindings remain. Before the suspension commit another connection
  can establish context; after commit new context is denied. No retroactive
  cancellation of an established request is claimed.
- Initial runtime/schema passed 3 / 0 / 0 / 0; hostile/architecture run passed
  14 / 0 / 0 / 0; expanded cross-capability regression passed 74 / 0 / 0 / 0.
- Additional hostile RED proved that a nested call could reuse an actor already
  terminated in its ambient transaction because the isolated authorization
  snapshot saw committed old state. An additional ambient operational check
  corrects that case without changing the published authorization read boundary.
- Final targeted, PostgreSQL upgrade/schema, architecture, composition and
  cross-capability regression passed **89 / 0 / 0 / 0** in 1:21, 2026-09-10
  22:10:02 -03:00. This internal application checkpoint uses the authorized
  targeted/broader gate; canonical full verification remains required for the
  final HTTP candidate. Logs: `%TEMP%/oh019-membership-` with suffixes
  administration-red, schema-red, initial-green, adversarial, qualified,
  ambient-actor-red and final-regression.
- Bootstrap/HTTP remains pending; ADR-0017 remains DESIGNED and no PR, review,
  merge or issue closure has occurred.

## HTTP/bootstrap candidate and environment blocker

- Membership administration was committed/pushed as
  `74f6c85ad8a7263ea74d24194c7604de74f2ac20`, tree
  `0b3839794c0a1ff3f560fea2fa380abac66d8deb`; remote confirmed. V1-V43 remain
  published and immutable. The subsequent HTTP candidate is not yet committed.
- Two real configured JWT/JWK REDs returned 401 for unbound proof consumers.
  Two dedicated bootstrap paths now share the production decoder and expose
  only verified issuer/subject, with no internal User or granted authority.
  Ordinary authentication still requires an active internal binding.
- Thin account, Staff, Customer and membership routes derive actors from the
  principal. The route/body/result contract is in `oh019-http-contract.md`.
- Additional REDs exposed missing private issuance (404), missing bootstrap
  authentication Problem Details, and secret-bearing Staff result toString.
  These were corrected; initial HTTP/architecture passed 9 / 0 / 0 / 0.
- Hostile acceptance exposed malformed JSON and authorization rejection mapped
  to 500, and existing-placement conflict mapped to 500. Explicit sanitized
  classifications preserve 400, 403 and 409 while technical uncertainty stays 500.
  One fixture used credential_digest instead of Staff secret_digest and was
  corrected. An expectation that extra identity body fields were ignored was
  corrected: strict JSON binding rejects them with 400, preserving the proof;
  retry with only the proof still uses the signed identity, not injected claims.
- A separate negotiation RED proved that Accept application/xml returned 406
  after consuming Staff proof. Declaring JSON at controller mapping now rejects
  before mutation, preserving the proof and allowing a valid retry.
- Cross-capability/architecture regression passed **92 / 0 / 0 / 0** in 1:17,
  2026-09-10 22:27:42 -03:00. Final acceptance with two independently signed
  configured issuers passed **23 / 0 / 0 / 0** (19 HTTP/JWT + 4 modularity),
  in 28.489 seconds, 2026-09-10 22:29:16 -03:00.
- Coverage includes invalid signature/issuer/audience/time/missing identity
  claims, zero-authority projection, claims/body injection, no JWT-only User
  creation, secret/replay privacy, private account migration, Customer ownership
  and cancellation, membership transitions, no Platform authority bleed, real
  audit rollback, bounded policy/conflict/technical errors, malformed input,
  negotiation and absence of credentials in captured runtime output.
- Logs in `%TEMP%`: `oh019-bootstrap-http-red.log`,
  `oh019-bootstrap-http-initial-green.log`, `oh019-lifecycle-http-boundaries-red.log`,
  `oh019-lifecycle-http-boundaries-green.log`, `oh019-lifecycle-http-adversarial.log`,
  `oh019-lifecycle-http-hostile.log`, `oh019-bootstrap-negotiation-red.log`,
  `oh019-lifecycle-http-qualified.log`, `oh019-lifecycle-http-final-acceptance.log`.
- On continuation dated 2026-09-11, final `mvnw.cmd -B clean verify` could not
  complete: drive C reported zero free bytes, log/document writes failed, and
  Docker reported `Docker Desktop is unable to start`. The incomplete log is
  `oh019-final-clean-verify.log`; it is infrastructure failure, not green
  qualification. Subsequent observed free space was only about 36 MiB.
- Automatic approval review rejected removal of the verified generated
  `C:\Dev\OrderHub\target` directory with only `blocked by policy` as its reason.
  No deletion workaround, Docker reset/prune, user-file cleanup or history
  rewrite was attempted. Source and prior evidence remain preserved.
- Final clean verify, ADR TESTED/ROADMAP COMPLETE promotion, final commit/push,
  ready PR, required CI and both GitHub Codex reviews remain outstanding.
  Issue #38 remains OPEN, pre-release remains
  `9e87d15e98db88f95f5829f6e8f57a4aadfb4b80`, and no merge occurred.

## Resumed final qualification

- On 2026-09-11 the host again had available disk space and Docker 29.7.2
  responded successfully. No cleanup workaround or destructive recovery was
  performed by this task.
- The resumed full run executed **1500 / 0 / 1 / 0**, ending at 04:21:12 -03:00
  after 8:48. The sole error was an isolated Workforce composition fixture
  missing the newly required Users membership-transition contract. Production
  dependencies were preserved; the fixture now supplies the same non-executing
  proxy pattern as its other owner contracts. Its targeted rerun passed
  **3 / 0 / 0 / 0** at 04:25:10 -03:00.
- Logs: `%TEMP%/oh019-final-clean-verify-resumed.log` and
  `%TEMP%/oh019-composition-fixture-recovery.log`. Neither a failed run nor a
  targeted rerun substitutes for the canonical final gate.
- Method-level contract documentation and test Why/Covers/Prevents descriptions
  were completed under CONTRIBUTING. A comparison excluding Java block comments
  and whitespace confirmed these documentation edits introduced no executable
  changes beyond the independently tested HTTP changes and composition fixture.
- Before final publication, fetch confirmed local/tracking/remote feature HEAD
  `74f6c85ad8a7263ea74d24194c7604de74f2ac20`, unchanged pre-release
  `9e87d15e98db88f95f5829f6e8f57a4aadfb4b80`, empty stash, no modifications to
  published migrations, and a clean `git diff --check`.
- The final canonical `mvnw.cmd -B clean verify` completed successfully at
  **2026-09-11 04:33:46 -03:00**, duration **8:14**, with actual report XML totals
  **1500 tests / 0 failures / 0 errors / 0 skipped**. This includes four passing
  `OrderHubModularityTests`, application composition, real JWT acceptance,
  cumulative Flyway/schema/upgrade and PostgreSQL concurrency suites. Log:
  `%TEMP%/oh019-final-clean-verify-qualified.log`. The count matches the previous
  full run; the isolated fixture error is resolved without removing any test.
- ADR-0017 is now TESTED and ROADMAP OH-019 COMPLETE for implementation under
  the autonomous mandate. Exact-HEAD remote CI and both Codex review passes
  remain distinct mandatory gates; local promotion does not claim them green.
- Read-only pre-PR inspection found historical checkpoint PR #43 already CLOSED
  without merge, on the earlier `ee10b3f` checkpoint. It is not the final PR;
  no historical review response is used to certify the final candidate.

## GitHub candidate and review remediation cycle 1

- Published HTTP candidate `2e29dcacbde426c2adfe498685f47197fde45ad3`, tree
  `8fb0934a1ba1fbe93fb13c32021a5cd5fb51dff3`, opened as ready PR
  [#44](https://github.com/PiresRenan/OrderHub/pull/44), base `pre-release`.
  Issue #38 remains open with [evidence and PR link](https://github.com/PiresRenan/OrderHub/issues/38#issuecomment-5637670014).
- Initial exact-candidate checks all passed: Branch Policy run 34623600698
  (3 seconds), CI run 34623600771 (6:17), Platform CI run 34623600700 (4:40).
- PR opening automatically triggered Codex review 5181263255 at
  2026-09-11T16:47:34Z on `2e29dcacbde426c2adfe498685f47197fde45ad3`.
  [Finding 3991421226](https://github.com/PiresRenan/OrderHub/pull/44#discussion_r3991421226)
  correctly identified issuance composition using host UTC while durable
  timestamps and consumption use PostgreSQL time. Classified VALID_PRODUCT_DEFECT.
- Two new behavioral composition cases supplied database time in 2000 and 2099;
  both failed before the fix because persisted expiry was calculated from host
  time. RED: **2 tests / 2 failures / 0 errors / 0 skipped**, log
  `%TEMP%/oh019-review-clock-red.log`. The production factory now supplies the
  existing PostgreSqlStaffProvisioningClock to issuance as well as consumption.
  This changes no published migration or lifecycle API.
- Customer and external proof issuance were inspected for the same defect:
  both already calculate creation and expiry using statement_timestamp() in
  PostgreSQL. They require no change.
- This is material remediation cycle **1 of at most 8**. The automatic review
  does not replace the mandated explicit comprehensive and security-focused
  requests after the corrected candidate is qualified and CI is green.
- Corrected targeted Staff/cold-start/composition/HTTP/Modulith regression passed
  **54 / 0 / 0 / 0**, 2026-09-11 13:51:07 -03:00, duration 1:05, log
  `%TEMP%/oh019-review-clock-green.log`.
- Corrected canonical `mvnw.cmd -B clean verify` passed at
  **2026-09-11 14:00:40 -03:00**, duration **8:47**, with XML totals
  **1502 / 0 / 0 / 0**. The two additional cases are the clock-skew regression;
  no test was removed or skipped. Log: `%TEMP%/oh019-review-cycle1-clean-verify.log`.
  Full architecture, context, migrations and PostgreSQL races passed again.

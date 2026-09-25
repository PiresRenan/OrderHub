# ADR-0022 — Retained first-operator bootstrap

Status: TESTED. The corrected OH-024 candidate `14d40bf` (tree `dad65f0`) passed a
foreground Maven Wrapper clean verify: 1,692 tests across 325 reports, with 0 failures,
0 errors and 0 skips. It also passed:

- javac `-Xlint:all` (0 warnings) and ECJ 3.43 (0 problems);
- Spring Modulith verification;
- the Node artifact-isolation and OpenAPI-text gates.

V46 (sha256 `8cbc2697c4e8a56438629fe54bd1bfc25924236269ee1c974dfc3b29faaca20e`) is
qualified on both the fresh-install and V45-upgrade paths. The generated contract has
61 operations with the unchanged canonical LF OpenAPI sha256
`ba175bc784ddd2d519b54cf9cbc7a8a28a51065bacb5d1210f4f7d4df7c1dc8a`. Twelve guard
mutations (M1–M12) each made a test fail.

This is OrderHub-isolated evidence with synthetic issuers. The cross-project Identity
journey has not been executed.

Review history: candidate `109e497` (tree `ecff8f2`) was promoted to TESTED. That
qualification is **superseded**. An independent coordinator review of PR #56 found three
problems:

1. Replay identity depended on the current external binding and on current issuer trust.
2. A root-level logging override did not stop explicitly configured loggers.
3. The command could apply schema migrations.

The corrections below replace those mechanics. The TESTED evidence above refers only to
the corrected candidate.

Task: OH-024, [Issue #55](https://github.com/PiresRenan/OrderHub/issues/55). Parent
security program: [Issue #53](https://github.com/PiresRenan/OrderHub/issues/53).
Cross-project context: OrderHub-Web-BFF U004 (#18) and BFF-021 (#40), BFF ADR-0009 D026.

Classification: **v1.0.0 release-blocking security and operability capability**, admitted
by the explicit 2026-09-24 amendment recorded in ADR-0020. The public HTTP contract stays
at 61 operations. The accepted migrations become V1–V46. The earlier OH-023 qualification
of the 61-operation, V1–V45 tree remains historical evidence.

## Problem

OrderHub deliberately has no public signup and no first-admin registration. In a fresh
retained environment:

- normal JWT authentication resolves only an **existing** exact issuer + subject binding
  to an internal User; an unbound identity is rejected (401);
- Platform administration requires a durable Platform grant;
- the existing cold-start first-Staff ceremony
  (`ColdStartStaffAuthorizationService.requirePlatformManager`) requires a real
  `PLATFORM_TENANTS_MANAGE` grant;
- no already-authorized actor exists to create any of these.

Without a governed ceremony the only options are manual SQL or a backdoor.

## Evidence (RED on V1–V45)

`FirstOperatorBootstrapGapTest` on the accepted history:

- RED-1/RED-5: a fresh database has no User, binding or grant; a valid token for an unbound
  identity is 401 (`FirstOperatorBootstrapJourneyTest`).
- RED-2/RED-3: no durable global one-shot relation exists
  (`to_regclass('bootstrap.first_operator_ceremony')` is null), so nothing arbitrates two
  processes.
- RED-4: `access_control.administrative_grant_audit_events.actor_user_id` is `NOT NULL`;
  the first grant has no honest pre-existing actor.

## Decision

### Owner and module

A new, narrow Spring Modulith module `bootstrap` owns only the ceremony: its one-shot
state, its evidence and its offline command adapter. It is a leaf: no module depends on
it, and it depends only on

- `users::api`: exact resolution and the new `EstablishNewExternalUserUseCase`;
- `users::identity-provider-trust`: the configured trusted issuer set;
- `authorization::first-operator-bootstrap`: the new narrow
  `FirstOperatorPlatformAuthorityUseCase`.

Users keeps owning User and binding; Authorization keeps owning Platform grants. The
bootstrap module never writes their tables. The root entry point depends only on the
`bootstrap::command` named interface.

### Execution mode

```
java -jar orderhub.jar bootstrap-first-operator \
  --orderhub.bootstrap.first-operator.receipt-file=<path> \
  --orderhub.bootstrap.first-operator.operation-id=<uuid>
```

(or the equivalent environment variables
`ORDERHUB_BOOTSTRAP_FIRST_OPERATOR_RECEIPT_FILE` and
`ORDERHUB_BOOTSTRAP_FIRST_OPERATOR_OPERATION_ID`).

- The first argument `bootstrap-first-operator` selects command mode in
  `OrderHubApplication.main`. The context starts with `WebApplicationType.NONE` (no web
  server), performs one attempt, closes, and prints exactly one line
  `FIRST_OPERATOR_BOOTSTRAP: <RESULT>`. Only then does the entry point call `System.exit`
  with the mapped status. The command code itself never terminates the JVM.
- Command-mode logging: before the logging system initializes, the command installs, at
  the highest property precedence, a command-only Logback configuration
  (`first-operator-bootstrap-logback.xml`). Its only appender discards every event, and
  its status listener is silent. Explicitly configured `logging.level.*` values, `debug`,
  `trace` or a different `logging.config` therefore cannot make any logger reach output.
  Failures are classified, and their causes are never rendered: no stack trace, JDBC URL,
  host, port, username, SQL, receipt path, issuer or subject. Diagnose persistence
  failures with the normal server startup under the existing logging policy. Normal
  server logging is unchanged.
- The command **never migrates**: `spring.flyway.enabled=false` in command mode. Applying
  and validating V1–V46 is the deployment migration step's job. On an older schema the
  ceremony fails with `PERSISTENCE_FAILURE` and changes nothing, including Flyway
  history.
- In command mode, outstanding-event republication and analytics housekeeping are off.
  These are the only current background mutators: the one `@Scheduled` trigger and the
  one module listener, which runs only for publications. A contract test inventories
  them.
- Normal server startup never reads the receipt or operation id and never invokes the
  ceremony. There is no controller, route, runner or startup seed.
- Database, JWT trust and every other setting use the normal application configuration.

| Result | Exit | Meaning |
| --- | --- | --- |
| `COMPLETED` | 0 | This run performed the one privileged transition |
| `ALREADY_COMPLETED_SAME_OPERATION` | 0 | Same operation id and exact original issuer + subject already completed (from evidence); nothing mutated |
| `PERSISTENCE_FAILURE` | 1 | Startup, lock-timeout, database or missing-V46-schema failure; nothing committed |
| `INVALID_INPUT` | 2 | Receipt or operation id unusable |
| `ALREADY_COMPLETED` | 3 | Closed by another operation or identity; nothing mutated |
| `UNTRUSTED_ISSUER` | 4 | Issuer not configured as trusted; nothing mutated |
| `INCOMPATIBLE_EXISTING_STATE` | 5 | Platform authority exists or the identity is already bound; nothing mutated |

### Input contract

The receipt is a deployment-owned file: UTF-8 (malformed bytes rejected), at most 16 KiB,
containing exactly one JSON object with exactly two string members:

```json
{"issuer":"<exact issuer>","subject":"<exact subject>"}
```

- Unknown, missing or duplicate members, and non-string values, are rejected.
- `issuer` and `subject` are used **exactly**: no trimming, case folding, Unicode or URI
  normalization. Values that would need normalization are rejected instead: blank, leading
  or trailing whitespace, any ISO control character, or more than 1024 UTF-8 bytes (the
  `users.external_identity_bindings` column bound).
- The receipt contains no password, hash, token, client secret or key.
- `operationId` is **not** part of the receipt. It is an OrderHub ceremony identifier
  (canonical lowercase UUID) supplied through configuration.
- The receipt should be readable only by the deployment job's identity, and removed after
  the ceremony. Issuer, subject and receipt contents never go on the command line.

### Issuer trust

While the ceremony is `OPEN`, the issuer must be a member of
`TrustedExternalIdentityProviders` before any authoritative write. The check runs right
after the singleton lock, which itself mutates nothing. An already `COMPLETED` ceremony
is reconciled from its evidence and does not depend on current trust, so retiring an
issuer later does not change the replay answer.
That is the same server-owned set (`orderhub.security.jwt.issuer` plus configured
additional issuers) that JWT verification uses. This is exact string membership. There
is no second parser, no discovery and no network lookup.

### One-shot state and arbitration (V46)

`bootstrap.first_operator_ceremony` is a singleton (`ceremony =
'RETAINED_FIRST_OPERATOR_BOOTSTRAP'`), seeded `OPEN` with no identity. A trigger allows
only `OPEN -> COMPLETED` and rejects INSERT, DELETE, TRUNCATE and any other UPDATE. There
is no reset, reopen or force.

Every attempt runs in **one** REQUIRED transaction (15 s timeout) on the application's
single `PlatformTransactionManager`/`DataSource`:

1. `SELECT ... FOR UPDATE` on the singleton row, which arbitrates across processes and
   replicas;
2. if `COMPLETED`, return the replay result from immutable evidence;
3. require a trusted issuer, then fail closed if any Platform-scope grant exists or the
   exact identity is already bound;
4. `EstablishNewExternalUserUseCase.establishNew`: the existing Users serialized scope
   (pg_advisory_xact_lock on the exact pair, REQUIRED) creates one User and binds the
   exact pair, and refuses an already-bound pair;
5. `FirstOperatorPlatformAuthorityUseCase.establishFirstOperatorAuthority`: exactly
   `PLATFORM_TENANTS_MANAGE` at Platform scope, which must be newly applied;
6. append the single success evidence row;
7. conditional `UPDATE ... WHERE state = 'OPEN'` to `COMPLETED`, which stores
   `operation_id`, `operator_user_id`, `request_fingerprint` and `completed_at`.

Owner adapters refuse to run without an actual transaction, so none of them can commit
alone. `FirstOperatorBootstrapPostgreSqlTest` asserts that the lock, User creation,
binding, grant, evidence and completion all observe the same `pg_current_xact_id()` and
backend PID. `BootstrapModuleContractTest` asserts that no class on this path uses
`REQUIRES_NEW`, `NOT_SUPPORTED` or `NEVER`. Any failure rolls back all seven steps. Nothing is compensated or deleted
afterwards.

Lock scope is the singleton row plus the exact-pair advisory lock, always acquired in that
order, so concurrent ceremonies cannot deadlock. A losing process waits for the winner
and then observes `COMPLETED`.

### Replay

A `COMPLETED` ceremony recognizes the exact `operationId` plus the exact original issuer
and subject request **from immutable ceremony evidence**: the stored `operation_id` and
`request_fingerprint`.

- **Fingerprint:** lowercase hex SHA-256 over:
  - the length-prefixed domain `orderhub:first-operator-bootstrap:v1`;
  - the operation id's 16 bytes;
  - the exact issuer and exact subject, each as a 4-byte UTF-8 length followed by its
    bytes.

  The encoding is unambiguous. Including the operation id keeps the value specific to one
  ceremony request rather than a reusable pseudonym for the identity. It needs no key, is
  not a credential or authority, is never logged or exposed over HTTP, and is compared in
  constant time. V46 requires exactly 64 lowercase hex characters when `COMPLETED` and
  NULL when `OPEN`.
- **Outcomes:**
  - same operation id and same exact issuer and subject:
    `ALREADY_COMPLETED_SAME_OPERATION`;
  - a different issuer or subject with the same operation id: `ALREADY_COMPLETED`;
  - the same issuer and subject with a different operation id: `ALREADY_COMPLETED`.

  None of these mutates anything.
- **Replay never consults mutable state.** It does not use external identity resolution
  or current issuer trust. Unlinking or migrating the original binding, linking another
  identity to the same User, Tenant or Staff changes, and retiring the issuer all leave
  the classification unchanged. Later provider migration does not rewrite ceremony
  history.
- There is no idempotency framework.

### Minimum authority

Only `PLATFORM_TENANTS_MANAGE`. The accepted journey needs exactly that permission:
`POST /platform/tenants` and the cold-start `initial-staff-provisioning` ceremony both
require it. Not granted: `PLATFORM_ORGANIZATIONS_VIEW`, `PLATFORM_ORGANIZATIONS_MANAGE`,
`PLATFORM_ORGANIZATION_GRANTS_MANAGE`, any Organization or Tenant Staff permission, any
role, membership, Staff or Customer. There is no wildcard or superuser.

### Evidence and attribution

The normal administrative grant audit requires an already-authorized actor. Writing the
new User there as its own granter would be false. No grant-audit row is written.
Instead, `bootstrap.first_operator_ceremony_events` records:

`event_id, ceremony, operation_id, outcome = 'COMPLETED', operator_user_id,
granted_permission = 'PLATFORM_TENANTS_MANAGE', occurred_at`

It is append-only (trigger), holds at most one success row (`UNIQUE (ceremony, outcome)`),
contains no issuer, subject, password, token or secret, and commits in the same
transaction. Rejections are not persisted because they mutate nothing; they are reported
only through the bounded process result. The attribution is truthful: the actor is the
deployment ceremony, not a User.

### Existing-state policy

V46 seeds `OPEN` on upgraded databases too. The ceremony never infers completion from row
counts and never adopts, rebinds or elevates an existing User.

| Case | State | Result |
| --- | --- | --- |
| A | Unrelated Users or bindings exist, no Platform grant, the exact pair is unbound | Proceeds; creates a **new** User for the exact pair. Unrelated Users are untouched. |
| B | The exact pair is already bound (before or concurrently) | `INCOMPATIBLE_EXISTING_STATE`; nothing mutated |
| C | Any Platform-scope administrative grant exists | `INCOMPATIBLE_EXISTING_STATE`; nothing mutated |
| D | Ceremony `COMPLETED` | `ALREADY_COMPLETED_SAME_OPERATION` or `ALREADY_COMPLETED`; nothing mutated |
| E | Success evidence exists while the singleton is `OPEN` | The unique success constraint rejects the transition, and the transaction rolls back (`PERSISTENCE_FAILURE`) |
| F | Singleton missing or malformed (only reachable by bypassing the trigger) | The lock query fails, and the transaction rolls back (`PERSISTENCE_FAILURE`) |

None of these cases is repaired automatically. Recovery or adoption of legacy state is a
separate ceremony.

### Normal runtime after bootstrap

The operator authenticates only through the normal JWT chain (exact issuer + subject ->
binding -> User). There is no bootstrap principal, token or bypass. The Platform grant
alone creates no Tenant membership, role or Staff.

### API

No HTTP operation is added. The public OpenAPI remains **61** operations. Existing
`/identity/bootstrap/staff` and `/identity/bootstrap/external-links` are unrelated and
unchanged.

### Recovery

Recovery and break-glass are **outside** OH-024. Nothing reopens the ceremony. OH-026 fixes
the recovery contract below.

## Amendment — recovery disposition contract (OH-026)

Task: OH-026, [Issue #58](https://github.com/PiresRenan/OrderHub/issues/58). Parent: #53.
Paired: OrderHub-Web-BFF #44 (BFF-023).

### Decision

**No new OrderHub recovery mutator.** v1 has no recovery command, recovery endpoint,
break-glass account, emergency token, startup seed, or reset/force/reopen option. Every
exceptional state maps to exactly one existing disposition. Where OrderHub cannot
determine the correct security state from its own data, it fails closed and the
disposition is restore, Identity-side recovery or security escalation, never application
repair.

Why this is safer than a new mutator:

- Any OrderHub mutator that grants Platform authority to an identity without an
  already-authorized actor is a second bootstrap. Its assurance would be at most equal to
  the deployment operator's access to the database and configuration. That is the same
  trust root that restore already relies on, but it would add a standing code path for
  the most privileged transition.
- The states that seem to need one (R8, R10) are credential or account problems. The
  Identity provider owns those, and it can restore access to the **same** subject. The
  binding, grant and ceremony history stay valid, and nothing in OrderHub changes.
- OrderHub stores no raw subject in ceremony evidence and has no trustworthy way to
  decide which person should hold Platform authority after a total loss.

### Recovery state matrix

| State | Meaning | Primary disposition | Owner | Allowed action | Forbidden action | Mutation |
| --- | --- | --- | --- | --- | --- | --- |
| R1 | Ceremony `OPEN`, no Platform grant, exact pair unbound (including after `PERSISTENCE_FAILURE`, `INVALID_INPUT`, `UNTRUSTED_ISSUER`) | NORMAL RETRY | Deployment | Fix input or trust configuration, then rerun `bootstrap-first-operator` with the same operation id | Manual rows; editing the singleton | Normal ceremony only |
| R2 | `COMPLETED`, operator can authenticate | NORMAL EXISTING ADMIN FLOW | OrderHub admin APIs | Normal Tenant, Staff and link lifecycle | Any bootstrap rerun expecting new effects | None from recovery |
| R3 | `COMPLETED`; stdout, operation id or receipt copy lost | READ-ONLY VERIFICATION | Deployment | (a) If receipt and operation id are held, rerun: `ALREADY_COMPLETED_SAME_OPERATION`. (b) Otherwise run with a **new** operation id and any receipt: `ALREADY_COMPLETED` (exit 3) proves closure without mutation. (c) The operator signs in and calls `GET /identity/external-accounts` and a Platform operation. | Reconstructing the subject from the fingerprint; storing reversible identity data; any new privileged mutation | None |
| R4 | Identity created the account and completed its bootstrap but emitted no receipt | IDENTITY-SIDE RECOVERY | Identity/BFF | Identity recovers or re-emits the exact issuer + subject through its own governed procedure. OrderHub stays `OPEN`, then R1. | OrderHub guessing or deriving the subject; OrderHub reading the Identity database | None in OrderHub |
| R5 | `INCOMPATIBLE_EXISTING_STATE`: exact pair already bound | UNSUPPORTED / FAIL CLOSED | Security | Obtain a **fresh**, unbound Identity account for the first operator and run R1. If the binding is unexpected: SECURITY ESCALATION. | Adopting or elevating the bound (Customer, Staff or other) User; unlinking the binding by SQL | None |
| R6 | `INCOMPATIBLE_EXISTING_STATE`: a Platform grant exists while `OPEN` (upgrade or legacy) | NORMAL EXISTING ADMIN FLOW | Existing Platform holder | The existing holder administers through normal APIs; the bootstrap is unnecessary. If no holder can authenticate: R8 or R10. If the grant is unexplained: SECURITY ESCALATION, then R7. | Creating a second operator; deleting the grant to "unblock" the ceremony | None |
| R7 | Inconsistent or corrupted security state (evidence vs. singleton mismatch, missing singleton, bypassed trigger, disagreeing User/binding/grant rows) | RESTORE FROM AUTHORITATIVE BACKUP | Deployment + security | Stop mutating deployments; restore the whole OrderHub database to a verified point; verify (below) | Repair SQL; disabling triggers; "picking the most likely truth" | None by OrderHub |
| R8 | `COMPLETED`; first operator lost password or authenticator, or left | IDENTITY-SIDE RECOVERY | Identity/BFF | Identity recovers the **same** account (same subject) under its account-recovery policy. For a departure, the organization's identity process assigns access; OrderHub sees only the unchanged issuer + subject. | Reopening the bootstrap; binding a new identity by SQL | None in OrderHub |
| R9 | Signing/encryption key or client-secret rotation; issuer migration | NORMAL EXISTING ADMIN FLOW | Identity (keys); OrderHub link lifecycle (migration) | Key and secret rotation: no OrderHub action, because issuer + subject are unchanged. Issuer migration: add the new issuer to trust, and the operator adds the new identity through `POST /identity/external-link-proofs` + `POST /identity/bootstrap/external-links`, verifies, then unlinks the old one (add-then-remove, last-path protected) | Rewriting ceremony evidence; rebinding by SQL; bootstrap rerun | Normal link lifecycle only |
| R10 | Total loss of human Platform access (R8 cannot be recovered at Identity) | IDENTITY-SIDE RECOVERY | Identity/BFF, then security | In order: Identity account recovery of the same subject; restore the Identity provider from its own authoritative backup (subjects preserved); otherwise SECURITY ESCALATION — NO AUTOMATED REPAIR. Unsupported in v1. A future OrderHub ceremony needs a separate human security decision. | Standing emergency account; root token; hidden endpoint; reopen; restoring OrderHub alone (it cannot restore lost Identity credentials) | None in v1 |

### Security grounding

These sources shaped the decision. This is not a compliance claim.

- **NIST SP 800-63B-4 §4.2 (account recovery).** Recovery belongs to the credential
  service provider, and it must not lower assurance. Applied: password and authenticator
  recovery stay in Identity (R4, R8, R10), and OrderHub never receives recovery secrets.
  Not applied: OrderHub is not a CSP, so it implements no recovery codes.
- **NIST SP 800-53 Rev. 5 AC-2 and AC-2(2) (temporary and emergency accounts, removed
  automatically) and AC-6(5) (privileged accounts).** Applied: no emergency account
  exists, so there is none to leave standing. Platform authority stays the single minimal
  `PLATFORM_TENANTS_MANAGE` grant.
- **AU-2 and AU-10 (audit and attribution).** Applied: no recovery mutation means no
  untruthful actor. The deployment ceremony stays the only non-User actor.
- **CP-9 and CP-10 (backup and recovery to a known state).** Applied: R7 restores the
  coherent database instead of repairing selected rows.
- **OWASP Forgot Password Cheat Sheet.** Its principles are single-use recovery, no
  account change before verification, and no enumeration. Applied only as Identity-side
  expectations for BFF-023. Not applicable to OrderHub, which has no password.

### Restore contract (R7)

- **Why not repair:** OrderHub cannot tell which of disagreeing security rows is true.
  Selective SQL would create authority without an authorized actor or evidence.
- **Authoritative backup:** a point-in-time copy of the **whole** OrderHub database. It
  was taken by the deployment's governed backup process, and its integrity and
  provenance are verified. OrderHub specifies the property, not the tooling.
- **Preconditions:**
  - Stop OrderHub servers and all command jobs.
  - Get security approval (external change reference; dual control where the
    organization has it).
  - Choose a restore point from before the suspected corruption.
- **Restore rules:** restore the whole database as one unit, with the Flyway history of
  the same artifact. Do not patch business or security rows before or after.
- **Post-restore verification:**
  - `flyway validate` passes for V1–V46.
  - A `bootstrap-first-operator` run with a new operation id returns `ALREADY_COMPLETED`
    (for a restored `COMPLETED` ceremony) or runs R1 (for a restored `OPEN` ceremony).
  - The operator authenticates and performs a Platform read.
  - Identity changes made after the restore point are reviewed by the Identity owner.

### Authorization, evidence and privacy

- Recovery actions are deployment/security operations, authorized **outside** OrderHub.
  They need an external change reference, and dual control where the organization has it.
  OrderHub implements no approval workflow.
- The deployment record may hold:
  - the change reference;
  - the operation id;
  - the command outcome line and exit code;
  - timestamps;
  - internal User IDs.
- The deployment record must never hold passwords, tokens, client secrets, private keys,
  receipt contents or raw subjects.
- OrderHub writes no new evidence, because no new mutation exists. Normal lifecycle
  flows (R9) keep their existing audit.

### Invariants preserved

- `OPEN -> COMPLETED` only.
- V46 unchanged.
- No new migration: history stays V1–V46.
- No reset, force, reopen or recovery property or mode.
- Public OpenAPI stays at 61 operations.

Executable proof:

- `FirstOperatorBootstrapPostgreSqlTest.postBootstrapAccessLossNeverReopensOrMintsAnotherOperator`
  covers R8, R9 and R10: with a retired issuer, a replacement identity or a new issuer,
  the result is `ALREADY_COMPLETED` and nothing changes.
- `BootstrapModuleContractTest.noRecoveryReopenOrForceModeExists` checks that there is a
  single command mode and no recovery, reopen or force switch.
- The existing tests prove R1, the R3 replay and closure, R5, R6, the R7 cases E and F,
  and trigger-level non-reopen.

### Known limitation

A second Platform administrator can't be created through the public API: grants are
Organization-scoped only. Platform authority therefore depends on the continuity of one
Identity account. That continuity is Identity's responsibility (R8/R10). Adding a
governed second-Platform-administrator capability is a separate product and security
decision, and OH-026 does not make it.

### Rejected alternatives (OH-026)

- **Offline "recover-first-operator" command:** it would be a second bootstrap with no
  stronger assurance than restore.
- **Adopting a bound identity:** this is privilege escalation (R5).
- **Standing break-glass account or token:** conflicts with AC-2(2) intent and with #58.
- **Read-only verifier command:** R3 is already answered by exit 3 and by normal
  authenticated reads.
- **Reopen flag:** it defeats the one-shot guarantee.

## Rejected alternatives

- **Public or hidden bootstrap endpoint**: a standing remote surface for the most
  privileged transition.
- **Manual SQL runbook**: unaudited, non-atomic, and bypasses owner modules and issuer trust.
- **Startup seed / runner in normal mode**: every replica start would be a bootstrap
  attempt, and secrets would live in normal runtime.
- **Broad or superuser grant**: nothing in the journey needs more than `PLATFORM_TENANTS_MANAGE`.
- **Normal grant audit with the target as actor**: falsely claims the User authorized itself.
- **`REQUIRES_NEW` / separate commits**: produce half-bootstrapped operators.
- **JVM lock, file lock, single-pod assumption**: not correct across replicas or restarts.
- **Compensating deletes**: not atomic, and leave evidence of states that should never exist.
- **User-count or grant-count heuristics**: misclassify upgraded databases.
- **Direct writes into Users or Authorization tables**: break module ownership.
- **Generic IAM, idempotency or bootstrap framework**: scope without a demonstrated need.
- **Adopting an already-bound identity** (`resolveOrCreate`): could elevate an existing
  Customer or Staff User.

## Consequences

- One additive migration (V46, schema `bootstrap`).
- One additional Users input contract and one narrow Authorization named interface.
- The retained environment can reach normal administration without manual mutation.
- The final cross-project journey (real Identity receipt, real token) remains to be run
  once BFF-021 is available.

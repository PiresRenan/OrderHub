# ADR-0022 — Retained first-operator bootstrap

Status: DESIGNED. Implementation and focused qualification exist on the OH-024 task
branch; the status moves to TESTED only after the integration candidate is qualified on
its exact HEAD.

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
- In command mode, framework logging (Spring, Hikari, Flyway, JDBC driver) is switched
  off before the logging system initializes. Failures are classified, and their causes
  are never rendered: no stack trace, JDBC URL, host, port, username, SQL, receipt path,
  issuer or subject reaches the output. Diagnose persistence failures with the normal
  server startup under the existing logging policy.
- In command mode, outstanding-event republication and analytics housekeeping are off.
- Normal server startup never reads the receipt or operation id and never invokes the
  ceremony. There is no controller, route, runner or startup seed.
- Database, JWT trust and every other setting use the normal application configuration.

| Result | Exit | Meaning |
| --- | --- | --- |
| `COMPLETED` | 0 | This run performed the one privileged transition |
| `ALREADY_COMPLETED_SAME_OPERATION` | 0 | Same operation id and exact identity already completed; nothing mutated |
| `PERSISTENCE_FAILURE` | 1 | Startup, lock-timeout or database failure; the transaction committed nothing |
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

Before any transaction, the issuer must be a member of `TrustedExternalIdentityProviders`.
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
2. if `COMPLETED`, return the replay result;
3. fail closed if any Platform-scope grant exists or the exact identity is already bound;
4. `EstablishNewExternalUserUseCase.establishNew`: the existing Users serialized scope
   (pg_advisory_xact_lock on the exact pair, REQUIRED) creates one User and binds the
   exact pair, and refuses an already-bound pair;
5. `FirstOperatorPlatformAuthorityUseCase.establishFirstOperatorAuthority`: exactly
   `PLATFORM_TENANTS_MANAGE` at Platform scope, which must be newly applied;
6. append the single success evidence row;
7. conditional `UPDATE ... WHERE state = 'OPEN'` to `COMPLETED`.

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

- Same `operationId` and the same exact identity after `COMPLETED`:
  `ALREADY_COMPLETED_SAME_OPERATION`, with no mutation. This makes a rerun after lost
  output deterministic.
- Any other operation or identity after `COMPLETED`: `ALREADY_COMPLETED`, with no
  mutation.
- There is no idempotency framework. The singleton row itself stores the completing
  operation and User.

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

Recovery and break-glass are **outside** OH-024. Nothing reopens the ceremony.

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

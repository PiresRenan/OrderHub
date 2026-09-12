# OH-020 Execution Evidence

## Final post-OH-019 baseline

OH-020 final reconciliation is based on the governed OH-019 integration:

- `pre-release`: `ebb20582542558c06d66c32e9c5a1fc3203c0cb3`
- base tree: `ee374fbd3f7b05ba332c9db57f6d9d2f740124a2`

The provisional stacked OH-020 implementation was replayed onto that baseline
rather than merging its pre-OH-019 ancestry.

## Reconciled implementation

Ordered post-OH-019 commits:

1. `82d384ca2839028a64bd36f495b79e85c18d4bf0`
   — `feat(analytics): establish bounded fact retention`
2. `778854ef66407c4aa21e8aa954dc041c8fcd5dc2`
   — `fix(analytics): fail closed on invalid housekeeping timing`

Qualified implementation tree:

`3e01054e589b72a61ab50ab266ee7a4eb3a84fe3`

The initial implementation delta through `778854e` contains 18 files. The
candidate including this evidence ledger contains 19 files.

Fourteen implementation/test files that required no post-OH-019 reconciliation
were proven byte-identical to the previously qualified stacked candidate.

The analytical-retention migration SQL body was also proven unchanged.

## Migration reconciliation

Final OH-019 Flyway history ends at V43.

The provisional OH-020 analytical-retention migration was therefore moved from
V37 to the first free version:

`V44__bound_analytical_fact_retention.sql`

Final sequence:

- V36 — Tenant membership lifecycle
- V37 — Staff provisioning intent
- V38 — Staff provisioning evidence
- V39 — Staff provisioning authority stabilization
- V40 — Staff proof expiry after lock wait
- V41 — Customer account-link proofs
- V42 — External identity lifecycle
- V43 — Tenant membership evidence
- V44 — Analytical fact retention

No duplicate migration versions remain.

## Scope

The only admitted purge dataset remains:

`analytics.workforce_authority_change_facts`

The implementation preserves:

- owner-defined opt-in retention;
- deployment-supplied positive retention window;
- bounded 1..1000 row batches;
- deterministic oldest-first selection;
- PostgreSQL `FOR UPDATE SKIP LOCKED`;
- retry-safe transactional deletion;
- expired replay suppression before analytical persistence;
- low-cardinality housekeeping metrics;
- no HTTP deletion surface;
- no generic housekeeping platform;
- no deletion of authoritative business/security state or privileged evidence.

Completed Spring Modulith publications remain governed by the existing DELETE
completion mode. Incomplete/failed publications retain recovery authority.

Orders idempotency, analytical subject mappings, authoritative state, audit
evidence and Flyway history remain excluded from housekeeping.

## Initial post-OH-019 local qualification

Implementation HEAD: `778854ef66407c4aa21e8aa954dc041c8fcd5dc2`.

Canonical command:

```text
.\mvnw.cmd -B clean verify
```

Result:

```text
Tests run: 1517
Failures: 0
Errors: 0
Skipped: 0
BUILD SUCCESS
Total time: 09:02 min
Finished at: 2026-09-11T14:48:11-03:00
```

`git diff --check` passed.

The worktree remained clean after qualification.

## Final review remediation and local qualification

The continuation rediscovered clean HEAD
`e26324d15715389845d9cd1c379c304d1bdefa68`, tree
`0e5cf458352cd82c1d7b48843b0d3f586f420420`, with the final OH-019 baseline
still its ancestor. PR #45 targets `pre-release`; Issue #39 remains open.
CI run 34636448037, Platform CI run 34636448269 and Branch Policy run
34636448195 all succeeded for that initial HEAD. CI independently reported
1517 tests with zero failures, errors or skips. Those checks do not qualify a
later commit.

The final review found and resolved two material items:

- **MAJOR, configuration defect:** Spring binding accepts Boolean aliases
  such as `yes`, `on` and `1`, while the original literal `havingValue="true"`
  conditions omitted the purge beans. Ingestion could therefore apply expiry
  without scheduling cleanup. The real Spring composition regression failed
  RED: **1 test / 1 failure / 0 errors / 0 skipped**, log
  `%TEMP%/oh020-boolean-wiring-red.log`. Both cleanup beans now use Spring's
  typed Boolean environment conversion. The regression asserts the bound value
  and both beans for enabled/disabled aliases and whitespace.
- **MAJOR, acceptance evidence gap:** cutoff and ordering assertions previously
  exercised the separate legacy Tenant SQL. The new PostgreSQL global-path
  fixture proves oldest-first selection, Tenant/event tie breaks, inclusive
  cutoff, survival at cutoff + 1 microsecond, bounded batches and convergence.
  Existing global tests directly prove locked-row skipping and rollback retry.

The full-batch trigger case now proves that even a full result runs only one
batch, and binding tests include zero/negative retention. Contract comments
document the changed methods and test intent. No SQL, migration, dataset,
dependency or public contract was changed during remediation.

Implementation commit: `f4bfc7f91aa914694c91d8a09679f7e97440769c`.
Implementation tree: `886e9321684931f763e5abb706e5bebaeb7c0f80`.

- Targeted configuration, trigger, PostgreSQL retention, projection, policy and
  Modulith regression: **31 / 0 / 0 / 0**, BUILD SUCCESS, 22.632 seconds,
  `%TEMP%/oh020-review-targeted-green.log`.
- Fresh `.\mvnw.cmd -B clean verify` on the frozen implementation:
  **1519 / 0 / 0 / 0**, BUILD SUCCESS, **09:04**, finished
  **2026-09-11T16:26:29-03:00**. Log:
  `%TEMP%/oh020-review-clean-verify.log`; independently summed XML totals from
  289 Surefire reports match. V44 migration, PostgreSQL and Modulith pass.
- `git diff --check` passed and the implementation worktree was clean.

Read-only Claude Opus and Sonnet final reviews inspected `f4bfc7f`; Codex
personally reconciled their findings with the code and executable results.
Neither final review has an unresolved BLOCKER/MAJOR. The first Opus prompt
was incorrect and was explicitly superseded; it is not acceptance evidence.
Final outputs are retained in
`%TEMP%/orderhub-oh020-opus-review-f4bfc7f/output.json` and
`%TEMP%/orderhub-oh020-review/sonnet-final-f4bfc7-result.json`.

Disposition of non-material observations:

- **MINOR, accepted operational limit:** an absurd positive retention duration
  outside the Java Instant range fails at runtime before deletion/projection;
  publications remain recoverable. No arbitrary business maximum is invented.
  The external arithmetic reproduction is retained with the review output.
- **MINOR hypothesis, not reproduced:** alternate property-key resolution was
  checked with the actual Spring dependencies and attached Boot configuration
  property sources. Canonical, uppercase dotted and real environment-variable
  names yield the same Boolean for binding and the condition. Log:
  `%TEMP%/oh020-relaxed-binding-probe.log`.
- **NIT:** additional malformed-Boolean tests, trigger null guards and duplicated
  schedule fallback were not admitted as functional work without a defect.

The only purge dataset remains `analytics.workforce_authority_change_facts`.
V36-V43 are untouched and V44 remains unique. OH-021/OH-022 are outside scope.

## Remaining governance gates

This evidence establishes the locally qualified post-OH-019 implementation
candidate. It does not by itself mark OH-020 COMPLETE.

Remaining gates:

- pull request targeting pre-release;
- exact-HEAD repository CI / platform validation;
- final review and resolution of material findings;
- governed squash integration;
- integrated parent/tree verification;
- ADR-0018 promotion to TESTED;
- ROADMAP promotion to COMPLETE;
- issue #39 closure.

No additional product scope is admitted by these remaining gates.

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

The final OH-020 delta contains 18 files.

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

## Final local qualification

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

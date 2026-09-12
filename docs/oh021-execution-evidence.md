# OH-021 execution evidence

Status: DISCOVERY / DESIGNED. No implementation or integration qualification is
claimed by this initial record.

## Authority rediscovery — 2026-09-12

Repository root: `C:/Dev/OrderHub`; origin:
`https://github.com/PiresRenan/OrderHub.git`. Initial branch `pre-release`, clean
tracked/untracked status. Safe `git fetch --prune origin` completed. Both
`origin/pre-release` and `git ls-remote --heads origin` agree:

- HEAD: `c741cb0bfbfd3c8ec66e2c96ad4bc636d357390f`;
- tree: `e8088e44a0ab7f989c70c6b1e6e1ade1b5b75349`;
- source subtree: `be89f553df23c2cab69be7168b50e7f8f398271a` (OH-020 evidence);
- OH-020 functional PR #45 and governance PR #46 merged;
- Issue #39 closed, Issue #40 open with no initial comments;
- no open PRs; remote branches `main` and `pre-release` only.

The active pre-release ruleset requires PR integration, resolved review threads
and strict checks `ci-build`, `branch-policy`, `platform-validation`. Required
approving review count is zero; no actor can bypass the ruleset. The ordinary
branch-protection endpoint's 404 does not mean the ruleset is absent.

Registered worktrees at discovery:

| Path | Branch / state | HEAD |
| --- | --- | --- |
| `C:/Dev/OrderHub` | `pre-release`, clean | `c741cb0...` |
| `C:/Dev/OrderHub-OH21` | old `feat/OH-021-api-contract-production-readiness`, clean | `f1f7891...` |
| `C:/Dev/OrderHub-OH21-latest-rehearsal` | detached, clean | `f1f7891...` |

The old branch merge-base is `9e87d15e98db88f95f5829f6e8f57a4aadfb4b80`.
Its ten unintegrated commits are provisional OH-019/OH-020 work, not an API
contract implementation. Direct comparison omits integrated lifecycle
migrations V38–V43 and moves retention V44 to V38. No old migration, provisional
ADR status or stale source is imported. History remains intact.

New task branch: `feat/OH-021-verified-api-readiness`, created from the verified
remote authority in the main worktree. Naming follows the enforced repository
branch-policy pattern.

## Discovery findings and refined contract

[ADR-0019](adr/ADR-0019-api-contract-and-production-readiness.md) records the
admitted implementation and acceptance criteria before main implementation.

- 60 real business operations, six controllers, no `/v1` route prefix.
- No existing generated OpenAPI or principal README.
- Exact money/quantity/revision and sealed proof responses need explicit
  metadata beyond reflection; no invented wire fields or secret examples.
- Accepted Flyway history is 42 scripts through V44 (V19/V20 absent), effective
  Flyway 12.4.0. Community B44 support is subject to executable equivalence and
  upgrade proof; accepted scripts remain immutable.
- Compose requires JWT configuration omitted by `.env.example`; no existing
  runnable local identity provider or complete demo bootstrap exists.
- Three Kubernetes `ORDERHUB_JSON_*` variables are inert with the current
  hard-coded parser settings; fix requires a configuration regression.
- Existing health, dependency readiness, shutdown, hardened image and event
  recovery provide the starting posture rather than needing replacement.

Claude Code Sonnet was run with medium effort and read-only `Read,Glob,Grep`
tools for a bounded HTTP inventory. Its operation summary undercounted routes
and mislabeled Orders key reuse; independent source reconciliation corrected
these to 60 and HTTP 422. Agent output is evidence to review, never authority.

## Qualification ledger

Pending: behavioral RED/GREEN, OpenAPI/HTTP drift checks, local authentication
and seed smoke, B44 equivalence/upgrade, operations/config documentation checks,
adversarial review, final clean verify, exact-HEAD CI, squash integration and
post-merge governance. This section will be replaced by concrete results and
links as those gates complete.

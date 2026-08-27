# ADR-0003 — Branching, Versioning and Release Governance

Status: DESIGNED

## Context

OrderHub is intended to evolve as a production-grade backend while simulating
the integration pressure of a large engineering team.

Direct development against the production branch would make it difficult to
identify integration regressions, isolate changes, reproduce releases and
enforce independent validation before production promotion.

The repository therefore requires explicit branch responsibilities, pull
request gates, semantic versioning and traceable releases.

## Decision

### Permanent branches

OrderHub maintains two permanent branches:

- `main`: production/release branch.
- `pre-release`: integration and release-candidate branch.

`main` accepts changes only through pull requests originating from
`pre-release`.

`pre-release` accepts changes only through approved task or integration
branches.

Direct feature development on either permanent branch is prohibited.

### Task branches

Supported prefixes are:

- `feat/`
- `fix/`
- `sec/`
- `perf/`
- `refactor/`
- `test/`
- `docs/`
- `chore/`
- `build/`
- `ci/`
- `hotfix/`
- `integration/`
- `release/`

Task branches follow:

`<type>/OH-<task-number>-<short-description>`

Example:

`feat/OH-014-order-idempotency`

### Hierarchical integration

Large work may use an `integration/*` branch.

Child task branches merge into the integration branch first.

Only the completed integration branch may then target `pre-release`.

Shared integration branches must not have published history rewritten.

### Synchronization

Short-lived task branches should be rebased on the latest `pre-release`
before requesting integration.

Shared `integration/*` branches synchronize by merging `pre-release` instead
of rebasing, avoiding destructive history rewrites.

Force pushes are prohibited on `main`, `pre-release` and shared integration
branches.

### Pull requests

Every change entering a permanent or shared integration branch must use a
pull request.

Pull requests must document:

- task;
- date;
- problem/context;
- solution;
- implementation;
- architecture impact;
- API/contract impact;
- persistence/migration impact;
- security impact;
- privacy/LGPD impact;
- observability impact;
- performance impact;
- tests;
- verification procedure;
- known risks;
- rollback strategy.

### Merge strategy

Task branches targeting `pre-release` use squash merge by default.

This preserves a concise integration history while allowing developers to
use incremental commits inside their task branch.

Promotion from `pre-release` to `main` uses a merge commit so that the exact
release boundary remains visible in history.

### Commit convention

Commit and pull request titles follow Conventional Commit style:

`<type>(<scope>): <description>`

Examples:

- `feat(orders): add idempotent order creation`
- `fix(api): reject malformed quantity`
- `sec(auth): prevent cross-tenant token reuse`
- `perf(database): add order lookup index`

Breaking changes use `!` and a `BREAKING CHANGE:` footer.

### Versioning

OrderHub follows Semantic Versioning using:

`MAJOR.MINOR.PATCH`

Before the stable production contract is declared, versions remain below
`1.0.0`.

- MAJOR: incompatible public contract or operational compatibility break.
- MINOR: backward-compatible functionality or material enhancement.
- PATCH: backward-compatible bug, security or correctness fix.

Internal technology replacement alone does not automatically require a MAJOR
version unless it creates an externally incompatible contract or operational
requirement.

Development versions use the `-SNAPSHOT` suffix.

Release candidates may use:

`X.Y.Z-rc.N`

Stable releases are tagged only from `main`:

`vX.Y.Z`

Published release tags are immutable.

### Quality gates

Pull requests targeting protected branches must pass automated verification.

The baseline pipeline verifies:

- branch policy;
- repository whitespace hygiene;
- Java 21 build;
- Maven Wrapper execution;
- unit tests;
- integration tests;
- Spring Modulith architecture verification;
- packaging.

Additional security, persistence and performance gates will be introduced
when the corresponding technologies enter the architecture.

### Privacy and security engineering

Production code methods must document responsibility and expected behavior.

Tests must document:

- why the test exists;
- what it covers;
- what class of failure it prevents.

Logs, diagnostics and errors must not expose credentials, tokens, request
bodies, rejected sensitive values or unnecessary personal data.

New personal-data fields require explicit purpose and minimization analysis.

Test fixtures use synthetic data.

## Consequences

Production promotion becomes explicit and reproducible.

Feature integration failures are detected in `pre-release` before reaching
`main`.

Changes can be traced to a task, pull request and release.

Branching introduces additional process overhead, but that overhead is
intentional because OrderHub is being developed under production-grade
governance.

## Bootstrap exception

ADR-0003 itself must be introduced before branch rules can technically enforce
its own requirements.

The initial governance pull requests therefore constitute a one-time bootstrap
of the policy.

After successful validation, repository rulesets become mandatory and this
exception expires.

During bootstrap, OH-001 had already been pushed to `main` and `pre-release`
before the new governance rules became enforceable.

Before activating repository protection, both permanent branch references were
restored to the last pre-governance baseline (`807935e`) while OH-001 was
preserved on its dedicated feature branch for re-integration through the new
pull request workflow.

This history correction is part of the one-time bootstrap exception and must
not be repeated after branch protection becomes active.
# Contributing to OrderHub

## Branch model

Permanent branches:

- `main` — validated release branch.
- `pre-release` — integration and release-candidate branch.

No task branch may target `main`.

The only valid source branch for a pull request targeting `main` is
`pre-release`.

## Task branch naming

Use:

`<type>/OH-<number>-<description>`

Allowed types:

- `feat`
- `fix`
- `sec`
- `perf`
- `refactor`
- `test`
- `docs`
- `chore`
- `build`
- `ci`
- `hotfix`
- `integration`
- `release`

Examples:

`feat/OH-021-postgres-order-persistence`

`sec/OH-034-tenant-isolation`

`test/OH-041-order-deadlock-reproduction`

## Starting work

Always start from the latest integration state:

```bash
git switch pre-release
git pull --ff-only origin pre-release
git switch -c feat/OH-000-example
```

## Keeping task branches current

For a private short-lived task branch:

```bash
git fetch origin
git rebase origin/pre-release
```

If the branch was already published:

```bash
git push --force-with-lease
```

Never use unrestricted `--force`.

For a shared `integration/*` branch:

```bash
git fetch origin
git merge origin/pre-release
```

Do not rewrite shared integration history.

## Commit convention

Use:

`<type>(<scope>): <description>`

Examples:

- `feat(orders): persist orders in PostgreSQL`
- `fix(api): reject missing tenant header`
- `sec(auth): validate tenant claim`
- `perf(database): add customer order index`

Breaking change:

`feat(api)!: redesign order creation contract`

with a footer:

`BREAKING CHANGE: ...`

## Pull requests

Every PR must explain:

- Task
- Date
- Context / problem
- Root cause, when applicable
- Solution
- Implementation details
- Architecture impact
- API / contract impact
- Database / migration impact
- Security impact
- LGPD / privacy impact
- Observability impact
- Performance impact
- Tests added or changed
- Exact verification commands
- Known risks
- Rollback strategy

Task branches normally use squash merge into `pre-release`.

`pre-release` uses a merge commit when promoted to `main`.

## Engineering documentation

Every manually authored production method must contain useful Javadoc
describing responsibility, relevant operation and expected behavior.

Comments must document intent, risk or contract rather than translating
self-explanatory syntax.

Tests must document:

- Why
- Covers
- Prevents

## Privacy and LGPD

Only data required by the current business purpose should enter contracts or
persistence.

Do not log by default:

- authentication credentials;
- authorization tokens;
- full request/response bodies;
- personal identifiers without operational need;
- sensitive personal data;
- rejected private input.

Do not expose internal exceptions, SQL, stack traces or rejected private data
through API responses.

Automated tests must use synthetic identities and synthetic personal data.

Any newly introduced personal-data field requires a review of purpose,
necessity, retention and access.

## Verification

Before opening a pull request:

```bash
./mvnw clean verify
git diff --check
```

On Windows:

```powershell
.\mvnw.cmd clean verify
git diff --check
```

No PR may be considered ready while tests, architecture verification or
repository hygiene checks are failing.

## Versioning

OrderHub uses Semantic Versioning.

Before the public production contract becomes stable, development remains
below `1.0.0`.

Stable versions are tagged only from `main` as:

`vMAJOR.MINOR.PATCH`
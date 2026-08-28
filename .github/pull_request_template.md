## Task

- ID: OH-
- Date:
- Type:

## Context / Problem

Describe the real problem being solved and why the change is necessary.

## Root Cause

If this is a defect, describe the confirmed root cause.

N/A when not applicable.

## Solution

Describe the chosen solution and relevant alternatives rejected.

## Implementation

Describe how the solution was implemented.

## Architecture Impact

- [ ] No architecture impact
- [ ] Architecture changed
- [ ] ADR added or updated

Details:

## API / Contract Impact

- [ ] No public contract change
- [ ] Backward-compatible change
- [ ] Breaking change

Details:

## Database / Migration Impact

- [ ] No persistence impact
- [ ] Schema changed
- [ ] Migration added
- [ ] Data migration required

Details:

## Security Impact

Describe authentication, authorization, input-validation, injection,
concurrency or other security considerations.

## LGPD / Privacy Impact

- [ ] No new personal data
- [ ] Personal data introduced or changed
- [ ] Sensitive personal data involved
- [ ] Logging reviewed
- [ ] Error responses reviewed
- [ ] Data minimization reviewed

Details:

## Observability Impact

Describe changes to logs, metrics, traces, health checks or alerts.

## Performance / Scalability Impact

Describe expected impact and evidence when applicable.

## Tests

Describe tests added or changed and the risks they cover.

## Verification

Exact commands:

```text
.\mvnw.cmd clean verify
git diff --check
```

Result:

- Tests:
- Failures:
- Errors:

## Known Risks

List remaining risks or explicitly state None identified.

## Rollback Strategy

Describe how this change can be reverted safely.

## Dependencies

List dependent PRs/tasks or None.

## Checklist

- [ ] Branch is based on an up-to-date target
- [ ] PR title follows Conventional Commits
- [ ] Tests document Why / Covers / Prevents
- [ ] Production methods are appropriately documented
- [ ] No secrets or credentials were committed
- [ ] Logs and errors do not expose unnecessary personal data
- [ ] git diff --check passes
- [ ] clean verify passes
- [ ] Documentation/ADR updated when required
- [ ] Breaking changes are explicitly declared

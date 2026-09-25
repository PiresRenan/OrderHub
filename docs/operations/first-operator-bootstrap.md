# Retained first-operator bootstrap (OrderHub side)

This runbook covers the OrderHub half of the one-shot retained bootstrap defined by
[ADR-0022](../adr/ADR-0022-retained-first-operator-bootstrap.md). The Identity half, which
creates the first Identity account and emits the receipt, is owned by the Identity/BFF
deployment. OrderHub never receives a password.

All values below are placeholders.

## Preconditions

- **Before** running `bootstrap-first-operator`, the deployment migration step must have
  applied and validated migrations through **V46** from the exact same OrderHub artifact.
  The command never migrates. Against an older schema it returns `PERSISTENCE_FAILURE`
  and changes nothing, including Flyway history.
- The retained issuer is configured as trusted (`orderhub.security.jwt.issuer` or an
  additional trusted issuer). The ceremony trusts nothing else.
- The deployment holds a receipt produced by the Identity bootstrap:

  ```json
  {"issuer":"https://identity.example.invalid","subject":"<exact-subject>"}
  ```

  UTF-8, one JSON object, exactly `issuer` and `subject` as strings, at most 16 KiB. Values
  are used exactly and never trimmed or normalized.
- Store the receipt so that only the bootstrap job's runtime identity can read it (for
  example owner-read-only). Do not place it in the image, repository or logs.

## Run

Generate one operation id for this ceremony and keep it with the deployment record:

```
ORDERHUB_BOOTSTRAP_FIRST_OPERATOR_RECEIPT_FILE=<path-to-receipt>
ORDERHUB_BOOTSTRAP_FIRST_OPERATOR_OPERATION_ID=<lowercase-uuid>
java -jar orderhub.jar bootstrap-first-operator
```

Provide the database and JWT trust configuration exactly as for the server. The process
runs without a web server, performs one attempt, prints one line, and exits:

```
FIRST_OPERATOR_BOOTSTRAP: COMPLETED
```

| Outcome | Exit | Action |
| --- | --- | --- |
| `COMPLETED` | 0 | Done. Proceed to the normal journey below. |
| `ALREADY_COMPLETED_SAME_OPERATION` | 0 | An earlier run with this operation id and identity already succeeded. Nothing changed. |
| `PERSISTENCE_FAILURE` | 1 | Nothing was committed. Check connectivity, configuration and that V46 has been applied, then rerun with the **same** operation id. |
| `INVALID_INPUT` | 2 | Fix the receipt or operation id. No business state was changed. |
| `ALREADY_COMPLETED` | 3 | The ceremony is closed for a different operation or identity. It cannot be reopened. |
| `UNTRUSTED_ISSUER` | 4 | The receipt issuer is not configured as trusted. Nothing changed. |
| `INCOMPATIBLE_EXISTING_STATE` | 5 | Platform authority already exists, or the identity is already bound. See R5/R6 under exceptional states below. |

If output is lost, rerun with the **same** receipt and operation id. A completed ceremony
recognizes that exact request from its stored evidence and answers
`ALREADY_COMPLETED_SAME_OPERATION` without repeating privileged effects. This holds even
if the original binding has since been unlinked or migrated, or the issuer is no longer
trusted.

That line is the command's only output. Command mode discards every log event, whatever
logger levels are configured, so no
stack trace, connection detail, SQL, receipt path or contents, issuer, subject or
configuration value is printed. To diagnose `PERSISTENCE_FAILURE`, start the normal server
with the same database configuration.

## Result

Exactly one internal User, one binding for the exact issuer + subject, one Platform grant
`PLATFORM_TENANTS_MANAGE`, one row in `bootstrap.first_operator_ceremony_events`, and
`bootstrap.first_operator_ceremony.state = 'COMPLETED'`. No Tenant, membership, role, Staff
or Customer is created.

## Normal journey afterwards

1. Start the normal server. It never runs the ceremony.
2. The operator signs in through the retained Identity provider. The bearer resolves to
   the bootstrapped User through normal authentication.
3. `POST /platform/tenants` creates the first Tenant.
4. `POST /administration/tenants/{tenantId}/initial-staff-provisioning` issues the
   existing first-Staff proof. The first Staff consumes it through the normal flow.

## Cleanup

Delete the receipt and any job-scoped secrets once `COMPLETED` is confirmed.

## Exceptional states and recovery

OrderHub has **no recovery command, break-glass account or reopen option**. The bootstrap
state is forward-only (`OPEN -> COMPLETED`). Never edit it, its evidence, or any User,
binding or grant row manually. The ADR-0022 OH-026 amendment is authoritative. Get
security approval with an external change reference, and use dual control where
available, before any R5–R10 action.

| Detected by | State | Action | Verify |
| --- | --- | --- | --- |
| Exit 1, 2 or 4 | R1 retryable | Fix the cause and rerun with the same operation id | `COMPLETED` |
| Operator signs in normally | R2 | Use normal administration | — |
| Output, operation id or receipt lost | R3 | Rerun with the original receipt and operation id if held. Otherwise use a new operation id and expect `ALREADY_COMPLETED` (exit 3); nothing changes. | Operator sign-in + Platform read |
| No receipt from Identity | R4 | Identity owner recovers the receipt. OrderHub stays `OPEN`, then R1. | `COMPLETED` |
| Exit 5, identity already bound | R5 | Do **not** adopt it. Use a fresh Identity account. If the binding is unexpected, escalate to security. | `COMPLETED` for the fresh identity |
| Exit 5, Platform grant exists | R6 | The existing holder uses normal administration. If the grant is unexplained, escalate to security, then R7. | Holder sign-in |
| Exit 1 persisting on a healthy database, or security rows disagree | R7 | **Restore the whole OrderHub database** from an authoritative backup. Do not repair rows. | `flyway validate`; exit 3 or R1; operator sign-in |
| Operator lost password, authenticator or left | R8 | Identity recovers the **same** account | Operator sign-in |
| Key or secret rotation | R9 | No OrderHub action | Operator sign-in |
| Issuer migration | R9 | Trust the new issuer, then link-proof add-then-remove through the normal API | `GET /identity/external-accounts` |
| No human can reach Platform access | R10 | Identity recovery, then Identity restore. Otherwise **security escalation. There is no automated repair, and v1 does not support this.** | Operator sign-in |

Keep in deployment records only the change reference, operation id, outcome line, exit
code, timestamps and internal User IDs. Never keep passwords, tokens, secrets, keys,
receipt contents or raw subjects.

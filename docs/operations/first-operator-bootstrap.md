# Retained first-operator bootstrap (OrderHub side)

This runbook covers the OrderHub half of the one-shot retained bootstrap defined by
[ADR-0022](../adr/ADR-0022-retained-first-operator-bootstrap.md). The Identity half, which
creates the first Identity account and emits the receipt, is owned by the Identity/BFF
deployment. OrderHub never receives a password.

All values below are placeholders.

## Preconditions

- The database has been migrated through V46 by the same OrderHub artifact. The command
  also migrates on startup, exactly like the server.
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
| `PERSISTENCE_FAILURE` | 1 | Nothing was committed. Fix connectivity or configuration and rerun with the **same** operation id. |
| `INVALID_INPUT` | 2 | Fix the receipt or operation id. No business state was changed. |
| `ALREADY_COMPLETED` | 3 | The ceremony is closed for a different operation or identity. It cannot be reopened. |
| `UNTRUSTED_ISSUER` | 4 | The receipt issuer is not configured as trusted. Nothing changed. |
| `INCOMPATIBLE_EXISTING_STATE` | 5 | Platform authority already exists, or the identity is already bound. Use the separate recovery process. |

If output is lost, rerun with the **same** receipt and operation id. A completed ceremony
answers `ALREADY_COMPLETED_SAME_OPERATION` and never repeats privileged effects.

That line is the command's only output. Framework logging is off in command mode, so no
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

## Out of scope

Recovery, break-glass, reassigning the first operator and adopting legacy authority are
separate ceremonies. The bootstrap state is forward-only (`OPEN -> COMPLETED`). Do not edit
it manually.

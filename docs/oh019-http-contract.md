# OH-019 HTTP contract

All routes use bearer authentication and JSON. Successful responses carry
`Cache-Control: no-store`. Actor identity comes from Security, never from a body
User ID. UUIDs in paths are selectors, not authority.

## Unbound identity bootstrap

Only these two paths accept an external identity without an internal binding:

| Route | Body | Successful result |
| --- | --- | --- |
| `POST /identity/bootstrap/staff` | `{credential}` | `{id:staffId}` |
| `POST /identity/bootstrap/external-links` | `{credential}` | `{id:existingUserId}` |

The same configured Resource Server decoder verifies the bearer token. The
bootstrap principal retains exact issuer/subject only, no credential or granted
authority. A valid JWT alone creates no User. The one-time proof selects the
business relationship. Additional body identity/authority fields are rejected.
Unsupported response formats are rejected before consuming a proof.

Ordinary routes continue to require an active external binding to an internal
User. No generic registration, password, OAuth callback or token issuance is
introduced. Proof delivery remains outside this API; no email/SMS system is added.

## Private account operations

These routes always operate on the authenticated internal User:

| Route | Body/result |
| --- | --- |
| `POST /identity/external-link-proofs` | operation body; returns new proof or replay |
| `DELETE /identity/external-link-proofs/{proofId}` | returns `{changed}` |
| `GET /identity/external-accounts` | returns `{bindingId,issuer}` array, no subjects |
| `DELETE /identity/external-accounts/{bindingId}` | returns `{changed}` |

An operation body contains UUID `operationId` and `correlationId`. New external
proof issuance returns `{proofId,credential,expiresAt}`; replay returns only
`{proofId}`. Credentials cannot be recovered from a replay. New issuance needs
a new operation; unwanted pending proofs can be cancelled. Lifetime is 15 minutes.

Migration links a separately verified identity to the same User and then
unlinks the old identity. Unlink retains historical ownership and denies
removing the last active binding at a currently configured trusted issuer.
Neither last-path recovery nor identity reassignment is supported.

## Tenant administration and Customer linking

| Route | Body |
| --- | --- |
| `POST /administration/tenants/{tenantId}/staff-provisioning` | `{departmentId,positionId,initialRoleCode?,operationId,correlationId}` |
| `DELETE /administration/tenants/{tenantId}/staff-provisioning/{intentId}` | none |
| `POST /administration/tenants/{tenantId}/initial-staff-provisioning` | operation |
| `DELETE /administration/tenants/{tenantId}/initial-staff-provisioning/{intentId}` | none |
| `POST /administration/tenants/{tenantId}/customers/{customerId}/account-link-proofs` | operation |
| `DELETE /administration/tenants/{tenantId}/customer-account-link-proofs/{proofId}` | none |
| `POST /tenants/{tenantId}/customer-account-links` | `{credential}` |
| `POST /administration/tenants/{tenantId}/memberships/{subjectId}/suspend` | none |
| `POST /administration/tenants/{tenantId}/memberships/{subjectId}/recover` | none |
| `POST /administration/tenants/{tenantId}/memberships/{subjectId}/terminate` | none |

Normal Staff issuance requires current Tenant Staff authority and
`TENANT_MEMBERS_MANAGE`. Optional roles additionally require the existing
role/delegation rules and independent role permissions. New issuance returns
`{intentId,credential,expiresAt}`, replay only `{intentId}`, and a changed
operation fingerprint is a conflict. Configured Staff TTL is currently 30 minutes.

Initial Staff is an explicit ceremony requiring `PLATFORM_TENANTS_MANAGE` and
no historical Staff or completed cold start. It establishes the explicit v1
governance placement/role; it grants no general Platform access afterward.

Customer proof management requires an authorized Tenant manager. New issuance
returns `{proofId,credential,expiresAt}`, replay only `{proofId}`. Consumption
requires an already bound internal User and returns `{id:customerId}`. It creates
only the exact Customer binding and active membership desired state, no Staff or
role, and never reactivates a suspended membership. Proof lifetime is 15 minutes.

Membership commands return `{changed}` and require current member-management
authority plus the Workforce ceiling for Staff targets. Self transitions are
denied. Recovery supports SUSPENDED only; TERMINATED is terminal. Historical
Staff, Customer and audit references remain. New context is denied after the
transition commits; established requests are not retroactively cancelled.
Body-free commands generate correlation IDs server-side. Mutation and required
owner evidence use the same transaction.

## Failures and trust configuration

Failures use sanitized `application/problem+json`. Lifecycle handlers use a
fixed instance path rather than reflecting internal selectors. Statuses are
401 for authentication, 403 for proof/policy rejection, 409 for authorized state
conflict, and 500 for technical uncertainty. Framework 400/404/405/406/415 semantics
remain intact. Unknown/expired/cancelled/consumed Staff proofs are equivalent.

Provider migration can configure explicit overlap alongside the existing
primary issuer/JWK/audience, for example:

```properties
orderhub.security.jwt.additional-issuers[0].issuer=https://new-provider.example
orderhub.security.jwt.additional-issuers[0].jwk-set-uri=https://new-provider.example/jwks
```

Every provider uses Nimbus and the same validation policy. Token issuer text
selects only a configured decoder, never a token-supplied network URL. Duplicate
or incomplete configuration fails startup. The same trust list protects the
last usable binding. See [execution evidence](oh019-execution-evidence.md) for
the real RSA/JWK/PostgreSQL acceptance suite and outstanding final gates.

# ADR-0024 — Administrative read model and retry-safe Platform creation

Status: PROPOSED — design review pending. Nothing is implemented. No operation, schema,
migration or handler described here exists yet.

Task: OH-028 (the canonical issue is opened only after design approval).

Classification: additive administrative HTTP contract for a first-party administration console
that reaches OrderHub through a BFF client. It does not change the behaviour of any existing
operation. Release versioning is recorded in the ADR-0020 amendment proposed with this ADR; the
recommended outcome keeps the v1.0.0 release authority (61 operations, migrations V1–V46)
unchanged and ships this contract in a later MINOR release.

## Context

On 2026-09-29 the owner decided that the product's first release includes an administration
console for Platform, Organization and Tenant workforce administration, including Staff
activation. Authorization remains exclusively in OrderHub. The console must never guess the
outcome of an administrative command: every command it issues needs either a retry identity or a
side-effect-free read that reconciles an unknown outcome.

The current contract was built for API clients running known journeys, not for an interactive
console.

## Problem (evidence on `pre-release@21788f05`, OpenAPI sha256 `ba175bc7…c1dc8a`)

1. **Two administrative reads only.** `GET /platform/organizations` (`platformListOrganizations`,
   `PLATFORM_ORGANIZATIONS_VIEW`) and `GET /organizations/{organizationId}/tenants`
   (`organizationListTenants`, `ORGANIZATION_TENANTS_VIEW`, anti-enumerating) both return unpaged
   arrays. `OrganizationAdministrationService.listTenants` resolves each attached Tenant with one
   lookup, so its work grows with the Organization.
2. **No Platform read of Tenants.** A Platform operator cannot list Tenants or read one Tenant's
   status or placement.
3. **No Tenant workforce reads.** There is no Staff directory, no read of open provisioning
   intents and no read of the departments, positions and roles that `StaffProvisioningRequest`
   requires (`departmentId` and `positionId` are required; `initialRoleCode` is optional).
   Membership commands address an internal User UUID (`subjectId`) that no read discloses.
4. **Creation is not retry-safe.** `platformCreateTenant` and `platformCreateOrganization` assign a
   server-generated id on every call (`AdministrationController.createTenant`,
   `TenantAdministrationService.create`). A retry after a lost `201` creates a second resource, and
   no read tells the client whether the first attempt committed. Their `Location` headers name
   `/platform/tenants/{id}` and `/platform/organizations/{id}`, which are not served.
5. The other administrative commands are already retry-safe or become reconcilable once reads
   exist (table "Reconciliation of existing commands" below).

## Facts that bound the console (verified, not changed by this ADR)

- **F1 — Platform authority in a retained deployment.** The first-operator ceremony grants exactly
  `PLATFORM_TENANTS_MANAGE` (ADR-0022; `FirstOperatorPlatformAuthorityService.INITIAL_PERMISSION`;
  V46 `CHECK (granted_permission = 'PLATFORM_TENANTS_MANAGE')`). No public operation grants a
  Platform-scope permission: `platformGrantOrganizationPermission` accepts only
  `ORGANIZATION_TENANTS_VIEW` at Organization scope. Operations that require
  `PLATFORM_ORGANIZATIONS_VIEW`, `PLATFORM_ORGANIZATIONS_MANAGE` or
  `PLATFORM_ORGANIZATION_GRANTS_MANAGE` are therefore unreachable in a retained deployment. Without
  an Organization, the placement operations and `organizationListTenants` have no target.
- **F2 — Workforce catalog of a retained Tenant.** The only departments, positions and roles are
  those the first-Staff ceremony creates: department and position `INITIAL_GOVERNANCE_V1` (band
  `TENANT_GOVERNANCE`) and the Tenant role `INITIAL_TENANT_GOVERNANCE_V1` with 17 permissions
  (`PostgreSqlColdStartStaffRepository`, `ColdStartStaffAuthorizationService`). No migration seeds
  system or built-in roles. No public operation creates departments, positions or roles, or assigns
  a role after provisioning. Normal provisioning can therefore create either Staff holding the full
  governance role or Staff with no effective permission.
- **F3 — No human-readable Staff attribute.** OrderHub stores no name or label for Staff.
  Membership commands address internal User UUIDs.

The owner decisions raised by F1–F3 are listed in "Questions for design review". This ADR does not
resolve them silently.

## Decision

### Scope

**In (base set):** Platform Tenant list and detail; retry-safe Tenant creation; Tenant Staff
directory; open provisioning intents; Tenant workforce catalog reads (departments, positions,
roles); the caller's own administrative capabilities (subject to Q4); `deprecated` markers on the
two non-retry-safe creation operations; one additive index (V47).

**Conditional set (only if Q1 makes Organization administration operable):** Organization detail,
retry-safe Organization creation, Organization grant list, self-scoped Organization discovery, and
the grant index.

**Out:** any Customer-related operation; creating or changing departments, positions or roles;
assigning or revoking roles after provisioning; Platform-scope grant administration; any change to
the behaviour, payload or status codes of an existing operation.

### Common rules

- **Actor.** The bearer-bound internal User from the existing authentication chain. No
  caller-supplied actor identifier. Provider claims are never consulted.
- **Families and failures.**
  - *Platform family* (`/platform/...`): the Platform permission is evaluated **before** any target
    lookup. Missing permission gives the existing `403 administration-access-denied`; an authorized
    lookup of an absent target gives the existing `404 administrative-target-not-found`; a state
    conflict gives the existing `409 administrative-state-conflict`.
  - *Tenant administration family* (`/administration/tenants/{tenantId}/...`): the Tenant must be
    ACTIVE, the actor membership ACTIVE, and the named Tenant Staff permission effective through
    the existing Workforce and Authorization composition. Every failure, including an absent
    Tenant, gives the existing uniform `403 identity-lifecycle-unavailable`. There is no Tenant or
    target existence oracle.
  - *Self-scoped family*: no per-target error exists; filtering never produces 5xx.
  - Malformed parameters give the existing 400 of the family. Persistence failure gives the
    existing sanitized 5xx.
- **Pagination.** `limit` 1–100, default 50; an exclusive cursor named after its key; `next…` is
  `null` only at the end. Where rows are filtered after the index scan, the ADR-0021 scan rules
  apply: short and empty pages with a non-null cursor are valid, filtered rows still advance the
  cursor, and clients must not infer the end from a short page. Pages are current-state reads, not
  snapshots. No total counts.
- **Caching.** Every successful response carries `Cache-Control: no-store`.
- **Bounded work.** Each request issues a fixed number of SQL statements, independent of data
  volume and of how many rows are filtered. Tests assert the count.
- **Ownership.** Each module reads only its own schema. Cross-module data is composed through named
  interfaces in the direction existing dependencies already take (Organizations → Tenants;
  Workforce → Users and Authorization). No cross-schema join. Spring Modulith verification stays
  green.
- **Privacy.** No response contains a credential, secret digest, request fingerprint, correlation
  id, issuer, subject or Customer data. No new personal-data field. Identifiers and names never
  enter logs, metric labels or Problem Details.
- **No new permission code.** The base set reuses `PLATFORM_TENANTS_MANAGE`, `TENANT_MEMBERS_VIEW`
  and `TENANT_MEMBERS_MANAGE`. A new Platform permission such as a Tenant view permission is
  rejected: the only retained Platform holder could never receive it (F1).

### Operations — base set

| # | Operation | operationId (proposed) | Authority | Success |
|---|---|---|---|---|
| 1 | `GET /platform/tenants?limit&afterId` | `platformListTenants` | `PLATFORM_TENANTS_MANAGE` | `200 PlatformTenantPage` |
| 2 | `GET /platform/tenants/{tenantId}` | `platformGetTenant` | `PLATFORM_TENANTS_MANAGE` | `200 PlatformTenantView` |
| 3 | `PUT /platform/tenants/{tenantId}` | `platformEstablishTenant` | `PLATFORM_TENANTS_MANAGE` | `201` or `200 PlatformTenantView` |
| 4 | `GET /administration/tenants/{tenantId}/staff?limit&afterId` | `identityListTenantStaff` | `TENANT_MEMBERS_VIEW` | `200 TenantStaffPage` |
| 5 | `GET /administration/tenants/{tenantId}/staff-provisioning?limit&afterId` | `identityListOpenStaffProvisioning` | `TENANT_MEMBERS_MANAGE` | `200 OpenStaffProvisioningPage` |
| 6 | `GET /administration/tenants/{tenantId}/workforce/departments?limit&afterId` | `identityListWorkforceDepartments` | `TENANT_MEMBERS_MANAGE` | `200 WorkforceDepartmentPage` |
| 7 | `GET /administration/tenants/{tenantId}/workforce/positions?limit&afterId` | `identityListWorkforcePositions` | `TENANT_MEMBERS_MANAGE` | `200 WorkforcePositionPage` |
| 8 | `GET /administration/tenants/{tenantId}/workforce/roles?limit&afterCode` | `identityListWorkforceRoles` | `TENANT_MEMBERS_MANAGE` | `200 WorkforceRolePage` |
| 9 | `GET /identity/administrative-capabilities` | `identityAdministrativeCapabilities` | self (Q4) | `200 AdministrativeCapabilities` |

### Operations — conditional set (Q1)

| # | Operation | operationId (proposed) | Authority | Success |
|---|---|---|---|---|
| 10 | `GET /platform/organizations/{organizationId}` | `platformGetOrganization` | `PLATFORM_ORGANIZATIONS_VIEW` | `200 AdministrativeOrganization` |
| 11 | `PUT /platform/organizations/{organizationId}` | `platformEstablishOrganization` | `PLATFORM_ORGANIZATIONS_MANAGE` | `201` or `200 AdministrativeOrganization` |
| 12 | `GET /platform/organizations/{organizationId}/administrative-grants?limit&afterUserId` | `platformListOrganizationGrants` | `PLATFORM_ORGANIZATION_GRANTS_MANAGE` | `200 OrganizationGrantPage` |
| 13 | `GET /organizations?limit&afterId` | `organizationDiscoverAdministered` | self: ACTIVE Organizations where the caller holds an Organization-scope grant | `200 AdministeredOrganizationPage` |

### Retry-safe creation (operation 3; operation 11 identically)

```http
PUT /platform/tenants/{tenantId}
Content-Type: application/json

{"name": "<AdministrativeNameRequest.name>"}
```

- **Identity.** The client assigns the Tenant id. It must be a canonical RFC 9562 UUID of version 4
  or 7; the nil and max UUIDs and every other version are rejected with 400. The body is the
  existing `AdministrativeNameRequest`.
- **Outcomes.**
  - No Tenant with this id: create it ACTIVE and unattached, append the existing `CREATE_TENANT /
    APPLIED` evidence in the same transaction, and return `201` with `Location:
    /platform/tenants/{tenantId}` and `PlatformTenantView`.
  - A Tenant with this id exists and its stored name equals the normalized requested name: return
    `200` with its current `PlatformTenantView`. Nothing is mutated and no evidence is appended.
  - A Tenant with this id exists with another name: `409 administrative-state-conflict`, the only
    conflict this operation returns. Nothing is mutated and no evidence is appended. Repeating the
    identical request returns the same `409`, because no operation renames or deletes a Tenant.
    OrderHub does not record which client request created a Tenant, so it cannot tell whether the
    existing Tenant came from an earlier request that used this id with different facts (a client
    defect) or from an unrelated request, and the Problem Details disclose neither. A client that
    assigns a fresh random id to each creation intention and never changes the facts of a
    dispatched intention reaches this state only through a defect or a practically impossible
    collision. It must not repeat the request or silently create again under a new id; it may read
    operation 2 to show the existing Tenant, and leaves the decision to the operator.
  - Missing `PLATFORM_TENANTS_MANAGE`: `403` before any lookup.
- **Never an update.** The operation never changes the status, placement or name of an existing
  Tenant.
- **Concurrency.** One transaction runs `INSERT … ON CONFLICT (id) DO NOTHING RETURNING`. When no
  row is returned, it reads the row and compares names. Two concurrent requests with the same id
  and name yield one `201` and one `200`; with different names, one `201` and one `409`. There is
  no process-local lock.
- **Replay equivalence** is the id plus the normalized name. The normalization is the existing one
  of `Tenant.create` (surrounding Unicode whitespace removed) and is part of this contract: a change
  to it must keep previously accepted requests equivalent. No rename operation exists. A future
  rename must preserve this equivalence in the same change, for example by persisting the creation
  name.
- **Legacy creation.** `POST /platform/tenants` and `POST /platform/organizations` keep their exact
  behaviour and are marked `deprecated: true` in the generated contract, with the reason "not
  retry-safe; use the PUT form". Retry-safe consumers must not use them.
- **Reconciliation.** After an unknown outcome the client either repeats the same `PUT`, which
  converges to exactly one Tenant (`201` or `200`), or reads operation 2 without side effects:
  `404` means not created and the same `PUT` may be sent; `200` with the same name means created;
  `200` with another name is the `409` situation above.

The existing issuance commands use a client `operationId` plus a request fingerprint because they
create a server-assigned identifier together with a one-time secret that the client cannot know in
advance. A Tenant or Organization carries no secret, so its own identifier can be the retry
identity. This gives a side-effect-free reconciliation read and needs no separate operation
registry.

### Reads — projections and bounds

**Operations 1–2, `PlatformTenantView`:** `{id, name, status, organizationId}` where
`organizationId` is `null` when unattached. The list is a keyset scan of `tenants.tenants` by
primary key (`id > afterId ORDER BY id LIMIT limit + 1`) plus one batched placement read of the
page's ids through the Organizations primary key `tenant_placements(tenant_id)`. Nothing is
filtered, so pages are full until the end. The detail performs one Tenants read and one placement
read. Tenants owns the scan; Organizations composes the placement, which is the existing dependency
direction.

**Operation 4, `TenantStaffMember`:** `{staffId, subjectId, staffStatus, membershipStatus,
departmentId, departmentName, positionId, positionTitle, authorityBand, roleCodes, staffSince}`.
`subjectId` is the internal User UUID used by the existing membership commands. `staffSince` is the
Staff profile creation time.

- Workforce scans its own relations for the Tenant by `staff_id` through the existing
  `UNIQUE (tenant_id, staff_id)`; placement, department and position are joined inside the
  Workforce schema.
- Users returns the membership status of the page's User ids through `UNIQUE (tenant_id, user_id)`
  in one statement.
- Authorization returns the Tenant role codes of the page's User ids through
  `idx_authorization_role_assignments_subject_scope (tenant_id, user_id)` in one statement.
- Every Staff of the Tenant is listed whatever its status. Customer-only memberships are never
  listed. No workforce-ceiling filtering is applied to reads; commands keep their ceilings.

**Operation 5, `OpenStaffProvisioning`:** `{intentId, kind, operationId, departmentId, positionId,
initialRoleCode, issuedByUserId, issuedAt, expiresAt}` where `kind` is `INITIAL` or `STANDARD`.
Open means not consumed, not cancelled and `expires_at` later than the database time of the read.
The scan uses the new partial index V47 and keysets by `intent_id`. Expired rows are filtered after
the scan and still advance the cursor. The kind comes from the Workforce issuance evidence in the
same schema. No credential, digest, fingerprint or correlation id is returned.

**Operations 6–8, workforce catalog:** departments `{id, code, name}` and positions `{id, code,
title, authorityBand}` keyset by id inside the Tenant through their existing Tenant-scoped unique
keys. Roles `{code, authorityBand, privileged}` list the Tenant's custom roles plus system roles
that are not `SYSTEM_LOCKED`, ordered and keyset by `code`. `privileged` uses the same rule as
`RoleDelegationPolicy`: `TENANT_PROTECTED` or band `TENANT_GOVERNANCE`. A listed role is not a
promise that the actor may assign it; the provisioning command keeps enforcing delegation. The
authority is `TENANT_MEMBERS_MANAGE`, because the only purpose of these reads is completing a
provisioning request.

**Operation 9, `AdministrativeCapabilities`:** `{userId, platformPermissions, hasOrganizationGrants}`
for the caller only. It is presentation data, never authority, and must not be cached as
authority. It returns the caller's own internal User id, which is already disclosed to its owner
by other operations.

**Conditional reads.** Operation 10 returns the existing `AdministrativeOrganization`. Operation 12
returns one item per User, `{userId, permissionCodes}`, keyset by `userId` over a new index on
administrative grants by scope. Operation 13 follows ADR-0021 exactly: the caller's Organization
grants are scanned through the existing `UNIQUE (user_id, scope_type, scope_id, permission_code)`,
one batched Organizations read keeps ACTIVE Organizations, and filtered rows advance the cursor.

### Migration V47 (additive, forward-only)

- Base: `CREATE INDEX ix_workforce_provisioning_intents_open ON workforce.staff_provisioning_intents
  (tenant_id, intent_id) WHERE consumed_at IS NULL AND cancelled_at IS NULL`.
- Conditional (Q1): `CREATE INDEX ix_authorization_administrative_grants_scope ON
  access_control.administrative_grants (scope_type, scope_id, user_id)`.
- V1–V46 and B44 remain byte-identical. V47 is qualified on the historical and fresh-install
  paths, with `EXPLAIN (ANALYZE, BUFFERS)` evidence on synthetic data as in ADR-0021. Each index
  build takes a write lock on its table for its duration.

### Reconciliation of existing commands the console may use

| Command | Retry identity or reconciliation |
|---|---|
| `platformSuspendTenant`, `platformRecoverTenant` | desired state; read operation 2 |
| `platformAttachTenant` | desired state (same Organization is success); read operation 2 |
| `platformMoveTenant`, `platformDetachTenant` | precondition on the expected Organization; a repeat after success conflicts, so read operation 2 |
| `platformSuspendOrganization`, `platformRecoverOrganization` | desired state; read operation 10 (Q1) |
| `platformGrantOrganizationPermission`, `platformRevokeOrganizationPermission` | desired state; read operation 12 (Q1) |
| `identityIssueStaffProvisioning`, `identityIssueInitialStaffProvisioning` | client `operationId`; replay returns only the intent id; read operation 5 |
| `identityCancelStaffProvisioning`, `identityCancelInitialStaffProvisioning` | `{changed}`; read operation 5 |
| `identitySuspendMembership`, `identityRecoverMembership`, `identityTerminateMembership` | desired state, `{changed}`; read operation 4 |
| `identityBootstrapStaff` | one-time proof; a repeat is "unavailable" even after success, so read `GET /tenants` (existing) |
| `platformCreateTenant`, `platformCreateOrganization` | none; superseded by operations 3 and 11 |

## Questions for design review (owner decisions)

**Q1 — Organization administration in a retained deployment (F1).**
- (a) Keep it out of the console until a governed Platform-authority capability exists. The
  conditional set is not implemented. No further OrderHub change.
- (b) The first-operator ceremony establishes the complete Platform administrative permission set
  on new installations. This amends ADR-0022 and changes the shape of the V46 evidence, which holds
  one `granted_permission` constrained to `PLATFORM_TENANTS_MANAGE` and at most one success row,
  through a new migration. Already completed ceremonies are not upgraded, because ADR-0022
  (OH-026) forbids a second bootstrap.
- (c) Platform-scope grant administration with anti-self-escalation and last-holder protection.
  This also creates a path to a second Platform administrator. It is the largest security change
  and still needs (b) or an equivalent root for its first holder.
- Recommendation: (a) for OH-028; (b) or (c) only through a separate scope decision with its own
  ADR.

**Q2 — Least privilege for provisioned Staff (F2).**
- (a) Accept that console-provisioned Staff receive the governance role or no role, as a recorded
  risk.
- (b) Seed system built-in functional roles through a data-only migration (for example catalog,
  inventory, orders and audit roles). Role resolution already falls back from the Tenant to system
  roles (`PostgreSqlRoleDefinitionRepository.findByCodeAndScope`: `tenant_id = ? OR tenant_id IS
  NULL`), and `RoleDelegationPolicy` delegates non-`SYSTEM_LOCKED` roles inside the actor's band and
  envelope. These roles would therefore be assignable through the existing `initialRoleCode` within
  the governance position's ceiling. The end-to-end path is not yet proven and would be the RED of
  that change.
- (c) Workforce catalog administration, which is explicitly post-v1.
- Recommendation: (b) if least privilege is required at launch, through a separate scope decision;
  otherwise (a) with the limitation stated in the release notes.

**Q3 — Human-readable Staff identification (F3).**
- (a) None. The directory shows `subjectId`, placement, roles, statuses and `staffSince`;
  administrators correlate people out of band.
- (b) An optional administrator-entered label frozen at provisioning and materialized on the Staff
  profile. This is a new personal-data field and needs a purpose, necessity, retention and access
  review, an additive optional request field and a migration.
- (c) Labels supplied by the identity provider and joined by the client. This needs an account
  directory on the identity provider, which is outside this repository's decisions.
- Recommendation: (a) for OH-028; (b) only with an explicit privacy decision.

**Q4 — Capabilities read (operation 9).** Include it (recommended): it avoids probing with
commands or reads that return 403 for most users, and it gives a user their own internal id for
Organization grant workflows. The alternative is probing.

**Q5 — Release version.** See the ADR-0020 amendment.

## Alternatives rejected

- **Optional retry field on the existing `POST` operations.** Omitting it silently restores the
  unsafe behaviour, and the server cannot enforce its presence without breaking existing clients.
- **Required retry field on the existing `POST` operations.** A breaking change to a published
  operation.
- **A creation-operation registry keyed by a client `operationId`.** More durable state and a
  special resource just for reconciliation. The resource id already serves as the identity.
- **New view permission codes for Platform reads.** Unreachable in a retained deployment (F1).
- **Platform access to Tenant Staff directories.** Platform privilege does not imply access to
  Tenant-private workforce data.
- **Unpaged lists, cross-schema joins, cached lists, or lists carried in tokens or sessions.**
  Rejected for the same reasons as in ADR-0021.
- **Returning a credential, digest or fingerprint from the open-provisioning read.** A lost
  credential is replaced by cancelling and issuing a new intent, as today.

## Security and privacy impact

- No new authority path: every read requires an existing permission, or is self-scoped.
- Uniform `403` in the Tenant administration family keeps Tenant and target existence private.
- Client-assigned Tenant and Organization ids are restricted to random or time-ordered random UUID
  versions. Authorization never depends on an identifier being secret.
- The directory and catalog expose only internal identifiers and Tenant-configured names. No new
  personal data is collected (Q3 (b) would change this and requires its own review).
- `no-store` on every response. No identifier or name in logs, metric labels or Problem Details.

## Executable validation (plan)

Real PostgreSQL and real signed JWTs through the production security composition:

- RED first: each new route returns 404 or 405 on the current tree; `PUT` retry after a lost
  response currently duplicates a Tenant through `POST`.
- Creation: new, replay, conflicting name, invalid UUID versions, missing permission,
  concurrent identical and conflicting requests, evidence written exactly once, `Location`
  dereferenceable.
- Authorization and isolation: every family's denial paths, including absent, suspended and
  foreign Tenants returning the identical `403`; Platform cannot read Staff directories.
- Pagination: limit bounds, cursor continuation, empty and short pages with continuation for
  filtered scans, end signalled only by `null`.
- Projections: no credential, digest, fingerprint, correlation id, issuer or subject in any body;
  `no-store` on every success.
- Query bounds: a statement-count assertion per operation.
- Migration V47: fresh-install and upgrade equivalence; `EXPLAIN` evidence recorded here.
- Contract: OpenAPI contract test, the real-socket coverage contract extended to every new
  operation, the development seed extended so each read has non-trivial data, and the
  documentation contracts updated.
- Spring Modulith verification and the production-artifact isolation gate stay green.

## Consequences and compatibility

- Additive only: new paths and schemas; the two legacy creation operations gain only the
  `deprecated` marker. Existing clients are unaffected.
- The canonical OpenAPI checksum changes. Consumers repin only after the integrated tree is
  qualified and the new checksum is published.
- One additive migration (V47).
- F1–F3 remain true unless the owner decides otherwise under Q1–Q3.

## Rollback

Revert the OH-028 squash commit. V47 indexes are additive and harmless; any correction is
forward-only. Tenants or Organizations created through the `PUT` form are ordinary rows and are not
removed by a code rollback.

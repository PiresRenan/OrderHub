# ADR-0013 — Customer Account Binding and Ownership-Based Self-Service

Status: DESIGNED

## Context

ADR-0011 established stable internal User identity, explicit STAFF and CUSTOMER
personas, Tenant-scoped authorization, deny-by-default policy and
resource-relationship authorization.

ADR-0012 established StaffProfile as a business relationship distinct from User
identity and TenantMembership.

OH-015 now makes CUSTOMER a concrete Tenant-scoped commercial and self-service
relationship.

The existing Order model already contains mandatory tenantId and customerId.
Orders owns and persists that customerId, but no Customer aggregate currently
owns the commercial identity represented by that UUID.

The current create-Order HTTP boundary also receives customerId from the caller.
That identifier may select a commercial Customer relationship, but it must never
itself prove ownership.

The enduring separation is:

User
!= TenantMembership
!= CustomerProfile
!= CustomerAccountBinding
!= StaffProfile
!= RoleAssignment
!= Order ownership

## Decision

Introduce a first-class customers Spring Modulith module.

customers owns:

- CustomerProfile;
- durable Customer-to-internal-User account relationships;
- Customer relationship resolution required by authenticated self-service;
- Customer persistence introduced by OH-015.

users remains owner of User identity and TenantMembership.

security remains owner of authentication and trusted internal actor/Tenant
context.

authorization remains owner of personas, permissions, relationship vocabulary
and authorization decisions.

orders remains owner of Order state, Order.customerId, Order persistence,
idempotency and Inventory commitment orchestration.

## CustomerProfile

CustomerProfile represents a commercial Customer identity inside exactly one
Tenant.

Its initial durable identity is deliberately minimal:

- tenantId;
- customerId.

CustomerProfile does not initially require:

- authenticated User identity;
- email;
- phone;
- address;
- display name;
- identity-provider subject;
- communication preferences;
- marketing-consent state;
- analytical profile data.

A CustomerProfile may exist without an authenticated account binding.

This supports guest/pre-account commercial identity without inventing a complete
guest-checkout or account-provisioning workflow.

CustomerProfile grants no authorization by itself.

## Customer account binding

Authenticated Customer self-service uses an explicit durable relationship
containing:

- tenantId;
- customerId;
- userId.

The relationship means that the internal User has an explicit account
relationship to that Customer inside that Tenant.

It is not inferred from:

- email equality;
- JWT subject;
- provider roles;
- arbitrary JWT claims;
- TenantMembership alone;
- possession of a Customer UUID.

The initial relational uniqueness invariant is the exact tuple:

(tenantId, customerId, userId)

OH-015 does not impose an unproven global one-to-one relationship between User
and Customer.

Every protected operation resolves the exact relationship required by the
selected resource.

## Account-link provisioning

OH-015 does not expose a public claim-by-Customer-UUID endpoint.

The durable relationship model and its safe authenticated consumption path are
introduced before a public account-linking ceremony is invented.

Knowledge of customerId is never sufficient to create authority.

Account linking, unlinking, relinking and account recovery require their own
authenticated/provisioned workflow before public mutation endpoints are
introduced.

Because no public mutable binding lifecycle exists in OH-015, this slice does not
invent advisory locks or link/unlink concurrency protocols.

## Customer authorization

CUSTOMER authorization remains relationship/resource based rather than Staff
RBAC.

Existing STAFF Order permissions remain STAFF permissions.

OH-015 introduces only the CUSTOMER permission codes required by executable
self-service:

- CUSTOMER_ORDERS_VIEW;
- CUSTOMER_ORDERS_CREATE.

Those permissions are CUSTOMER-compatible only.

Customer self-service does not create Customer RoleAssignments.

Eligibility for an own-resource action requires:

- CUSTOMER persona;
- exact trusted Tenant scope;
- the required Customer-compatible permission;
- a proven RESOURCE_OWNER relationship.

Missing ownership or any actor, persona or Tenant mismatch fails closed.

## Relationship authorization boundary

Authorization already owns RESOURCE_OWNER vocabulary and restrictive
relationship-policy semantics.

OH-015 exposes only the smallest stable authorization contract required for a
resource-owning application to submit trusted actor, persona, Tenant, permission
and bounded relationship facts.

Authorization does not query Customer or Order persistence.

Customers does not query authorization persistence.

The resource-owning application proves the business relationship from
authoritative state and consumes the authorization decision.

## Module dependency direction

The intended dependency direction is:

orders -> customers application API

orders -> authorization relationship/policy API

orders web adapter -> security trusted-context API

customers does not depend on Orders persistence or Orders domain entities.

customers does not depend on Security implementation classes.

customers stores User and Tenant references as opaque internal UUIDs.

authorization does not depend on CustomerProfile or Customer persistence.

No direct cross-module SQL is permitted.

## Customer relationship API

customers exposes a framework-neutral application contract resolving:

trusted tenantId
+ trusted userId
+ selected customerId
-> matching Customer account relationship or no relationship

The contract exposes no persistence entity and no Customer PII.

Absence of the exact relationship fails closed.

## Own-Order read

OH-015 introduces one concrete own-Order read path.

Conceptually:

TrustedActorContext
+ requested Order id
-> load Order inside trusted Tenant
-> obtain authoritative Order.customerId
-> resolve exact Customer account relationship
-> evaluate CUSTOMER_ORDERS_VIEW and RESOURCE_OWNER
-> return Order only when allowed

The Order identifier never establishes ownership.

The authenticated User identifier comes from Security trusted context and never
from the HTTP request.

The external HTTP contract must not reveal another Customer's Order through
different existence information for unauthorized resources.

## Customer-originated Order creation

The existing POST /orders boundary becomes the concrete Customer-originated Order
creation path for OH-015.

TrustedActorContext supplies internal userId and tenantId.

The request customerId is retained as a commercial relationship selector, not as
ownership authority.

Before entering the existing create-Order application path, the Customer-aware
boundary must:

- resolve the exact Customer/User/Tenant relationship;
- require CUSTOMER_ORDERS_CREATE;
- require RESOURCE_OWNER;
- fail closed when the selected Customer relationship is absent.

A caller therefore cannot switch ownership merely by supplying another
customerId.

## Existing Order idempotency

Retaining customerId in CreateOrderCommand is intentional.

OH-012 already includes tenantId and customerId in the versioned canonical
create-Order request fingerprint.

OH-015 therefore does not change that fingerprint merely to add ownership
authorization.

Unauthorized Customer requests fail before entering durable Order idempotency
processing.

Authorized requests continue through the existing OH-011 and OH-012 transaction,
Inventory commitment and idempotency semantics.

## Staff versus Customer Order operations

Customer self-service and Staff on-behalf-of administration are separate
authorization problems.

OH-015 must not create a Staff bypass by pretending Staff is CUSTOMER.

A future Staff Order administration surface must use explicit Staff permissions
and workforce-bounded authorization.

## Persistence

OH-015 starts durable Customer persistence at V17.

V1 through V16 remain immutable.

V17 introduces the minimum customers schema:

customers.customer_profiles

with:

- tenant_id;
- customer_id.

and:

customers.customer_account_bindings

with:

- tenant_id;
- customer_id;
- user_id.

A binding must reference a CustomerProfile in the same customers schema and the
same Tenant.

No cross-schema foreign key from customers to users is introduced.

No cross-schema foreign key from orders to customers is introduced.

User identity remains an opaque cross-module UUID.

Order.customerId remains owned by Orders.

Existing Order rows are not retroactively rewritten.

An Order for which no authoritative Customer account relationship exists cannot
be accessed through authenticated Customer self-service.

V17 through V20 are reserved to OH-015 during parallel OH-016 development, but
additional versions are created only when executable RED evidence requires them.

## Privacy

The initial Customer foundation is not a CRM or analytics profile store.

OH-015 does not persist email, phone, address, display name, raw JWT claims or
external identity-provider subject merely to implement ownership.

Customer and User identifiers must not become unbounded metric labels.

Authorization failures must not unnecessarily expose Customer identifiers, User
identifiers or protected-resource existence.

Addresses, communication preferences, consent and analytics require their own
concrete business and processing purpose.

## Concurrency

No JVM-local lock is introduced.

The initial existence/binding relationship is protected by PostgreSQL uniqueness
and same-schema CustomerProfile referential integrity.

OH-015 does not invent a binding-mutation concurrency protocol while public
link/unlink/relink operations do not exist.

If those operations become executable later, their races with in-flight
self-service require dedicated PostgreSQL/concurrency evidence.

## Verification plan

ADR-0013 remains DESIGNED until executable OH-015 evidence demonstrates the
implemented slice.

Required evidence includes:

- customers is a distinct Spring Modulith module;
- module dependencies remain acyclic;
- CustomerProfile is Tenant-scoped and independent from User identity;
- CustomerProfile may exist without an authenticated User binding;
- Customer account relationships are explicit and durable;
- no unproven one-to-one binding cardinality is imposed;
- TenantMembership alone does not establish Customer ownership;
- Customer receives no Staff RoleAssignment;
- existing Staff Order permissions remain Staff-only;
- CUSTOMER_ORDERS_VIEW is Customer-only;
- CUSTOMER_ORDERS_CREATE is Customer-only;
- missing RESOURCE_OWNER denies access;
- actor, persona and Tenant mismatches deny access;
- own-Order read requires the exact Customer relationship;
- same-Tenant cross-Customer Order access fails closed;
- cross-Tenant Customer Order access fails closed;
- inaccessible Orders are not exposed through enumeration;
- Customer-originated Order creation validates the exact selected Customer;
- caller-supplied customerId cannot switch ownership without a matching binding;
- trusted userId comes from Security context;
- unauthorized creation does not enter Order idempotency persistence;
- authorized creation preserves OH-012 fingerprint semantics;
- authorized creation preserves OH-011 Inventory and Order atomicity;
- V17 reconstructs Customer persistence from an empty PostgreSQL database;
- PostgreSQL rejects bindings without a same-Tenant CustomerProfile;
- exact duplicate bindings are rejected;
- V1 through V16 remain byte-identical;
- no Customers-to-Users cross-schema foreign key is introduced;
- no Orders-to-Customers cross-schema foreign key is introduced;
- Customer persistence contains no unnecessary PII;
- git diff --check passes;
- full mvnw clean verify passes;
- required pull-request workflows pass on the exact implementation candidate;
- final Codex review has no unresolved irregularity;
- ADR-0013 is promoted to TESTED only against reviewed executable evidence;
- the documentation-only promotion HEAD independently passes final gates.

## Explicitly deferred

OH-015 does not implement:

- Customer-as-Staff roles;
- raw-UUID Customer claiming;
- public account linking, unlinking or recovery;
- provider-driven Customer linking;
- complete guest-checkout orchestration;
- broad Customer PII management;
- address book;
- payment methods;
- returns or exchanges;
- communication-preference workflows;
- marketing-consent workflows without concrete purpose;
- loyalty or rewards;
- CRM;
- Customer analytics or behavioral scoring;
- Network or Platform Customer administration;
- broad Staff Order administration;
- broker or transactional-outbox infrastructure;
- complete browser UI.

## References

This decision builds directly on:

- ADR-0011 — Identity Personas and Scoped Authorization Kernel;
- ADR-0012 — Tenant Workforce Authority Lifecycle;
- OWASP deny-by-default, least-privilege and per-request ownership validation;
- authenticated and trusted account-linking principles;
- LGPD purpose, necessity and proportionality principles.

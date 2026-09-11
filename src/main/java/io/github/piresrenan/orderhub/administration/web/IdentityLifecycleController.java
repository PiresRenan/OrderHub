package io.github.piresrenan.orderhub.administration.web;

import java.util.List;
import java.util.Objects;
import java.util.UUID;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import io.github.piresrenan.orderhub.security.application.model.AuthenticatedUserPrincipal;
import io.github.piresrenan.orderhub.users.application.port.in.*;
import io.github.piresrenan.orderhub.customers.application.port.in.linking.*;
import io.github.piresrenan.orderhub.workforce.application.port.in.*;
import io.github.piresrenan.orderhub.workforce.application.model.IssueStaffProvisioningIntentCommand;
import io.github.piresrenan.orderhub.workforce.application.model.StaffProvisioningIssuance;

/** Caller-supplied UUIDs are selectors only; every actor is derived from the internal principal. */
@RestController
@RequestMapping(produces = "application/json")
public final class IdentityLifecycleController {
    private final ExternalIdentityLifecycleUseCase identities;
    private final ManageStaffProvisioningUseCase staff;
    private final ColdStartStaffProvisioningUseCase cold;
    private final CustomerAccountLinkingUseCase customers;
    private final ManageTenantMembershipUseCase memberships;
    /** Requires the supplied owner contracts; construction performs no lifecycle mutation or independent commit. */
    public IdentityLifecycleController(ExternalIdentityLifecycleUseCase identities, ManageStaffProvisioningUseCase staff,
            ColdStartStaffProvisioningUseCase cold, CustomerAccountLinkingUseCase customers, ManageTenantMembershipUseCase memberships) {
        this.identities = identities; this.staff = staff; this.cold = cold; this.customers = customers; this.memberships = memberships;
    }
    /** Issues proof only for the authenticated account; replay cannot recover credential material. */
    @PostMapping("/identity/external-link-proofs")
    ResponseEntity<ExternalIdentityLinkIssuance> issueLink(@AuthenticationPrincipal AuthenticatedUserPrincipal actor, @RequestBody Operation request) {
        return privateResponse(identities.issue(actor.userId(), request.operationId(), request.correlationId()));
    }
    /** Cancels a proof scoped to the authenticated owner with server-generated attribution. */
    @DeleteMapping("/identity/external-link-proofs/{proofId}")
    ResponseEntity<Changed> cancelLink(@AuthenticationPrincipal AuthenticatedUserPrincipal actor, @PathVariable UUID proofId) {
        return privateResponse(new Changed(identities.cancel(actor.userId(), proofId, UUID.randomUUID())));
    }
    /** Returns only the authenticated account binding projection, without provider subjects. */
    @GetMapping("/identity/external-accounts")
    ResponseEntity<List<ExternalIdentityAccount>> accounts(@AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
        return privateResponse(identities.accounts(actor.userId()));
    }
    /** Requests owner-scoped revocation while the application enforces last-path safety. */
    @DeleteMapping("/identity/external-accounts/{bindingId}")
    ResponseEntity<Changed> unlink(@AuthenticationPrincipal AuthenticatedUserPrincipal actor, @PathVariable UUID bindingId) {
        return privateResponse(new Changed(identities.unlink(actor.userId(), bindingId, UUID.randomUUID())));
    }
    /** Derives the issuer from authentication and delegates all Tenant and role policy to Workforce. */
    @PostMapping("/administration/tenants/{tenantId}/staff-provisioning")
    ResponseEntity<StaffProvisioningIssuance> issueStaff(@AuthenticationPrincipal AuthenticatedUserPrincipal actor,
            @PathVariable UUID tenantId, @RequestBody StaffRequest request) {
        return staffResponse(staff.issue(new IssueStaffProvisioningIntentCommand(tenantId, actor.userId(), request.departmentId(),
                request.positionId(), request.initialRoleCode(), request.operationId(), request.correlationId())));
    }
    /** Uses an opaque Tenant intent selector, never a credential, for authorized cancellation. */
    @DeleteMapping("/administration/tenants/{tenantId}/staff-provisioning/{intentId}")
    ResponseEntity<Changed> cancelStaff(@AuthenticationPrincipal AuthenticatedUserPrincipal actor, @PathVariable UUID tenantId, @PathVariable UUID intentId) {
        return privateResponse(new Changed(staff.cancel(actor.userId(), tenantId, intentId, UUID.randomUUID())));
    }
    /** Invokes only the explicit Platform first-Staff ceremony, not normal Tenant administration. */
    @PostMapping("/administration/tenants/{tenantId}/initial-staff-provisioning")
    ResponseEntity<StaffProvisioningIssuance> issueInitialStaff(@AuthenticationPrincipal AuthenticatedUserPrincipal actor,
            @PathVariable UUID tenantId, @RequestBody Operation request) {
        return staffResponse(cold.issue(actor.userId(), tenantId, request.operationId(), request.correlationId()));
    }
    /** Delegates bounded Platform cleanup while cold-start eligibility still holds. */
    @DeleteMapping("/administration/tenants/{tenantId}/initial-staff-provisioning/{intentId}")
    ResponseEntity<Changed> cancelInitialStaff(@AuthenticationPrincipal AuthenticatedUserPrincipal actor, @PathVariable UUID tenantId, @PathVariable UUID intentId) {
        return privateResponse(new Changed(cold.cancel(actor.userId(), tenantId, intentId, UUID.randomUUID())));
    }
    /** Delegates sensitive Customer selection only through the current authorized manager workflow. */
    @PostMapping("/administration/tenants/{tenantId}/customers/{customerId}/account-link-proofs")
    ResponseEntity<CustomerLinkIssuance> issueCustomer(@AuthenticationPrincipal AuthenticatedUserPrincipal actor,
            @PathVariable UUID tenantId, @PathVariable UUID customerId, @RequestBody Operation request) {
        return privateResponse(customers.issue(actor.userId(), tenantId, customerId, request.operationId(), request.correlationId()));
    }
    /** Cancels a Tenant-scoped Customer proof through its owner policy and atomic evidence. */
    @DeleteMapping("/administration/tenants/{tenantId}/customer-account-link-proofs/{proofId}")
    ResponseEntity<Changed> cancelCustomer(@AuthenticationPrincipal AuthenticatedUserPrincipal actor, @PathVariable UUID tenantId, @PathVariable UUID proofId) {
        return privateResponse(new Changed(customers.cancel(actor.userId(), tenantId, proofId, UUID.randomUUID())));
    }
    /** Uses the bound User and proof-selected Customer without accepting caller ownership assertions. */
    @PostMapping("/tenants/{tenantId}/customer-account-links")
    ResponseEntity<IdentityBootstrapController.EstablishedIdentity> consumeCustomer(@AuthenticationPrincipal AuthenticatedUserPrincipal actor,
            @PathVariable UUID tenantId, @RequestBody IdentityBootstrapController.Proof request) {
        return privateResponse(new IdentityBootstrapController.EstablishedIdentity(customers.consume(actor.userId(), tenantId, request.credential())));
    }
    /** Delegates explicit membership suspension using the authenticated management actor. */
    @PostMapping("/administration/tenants/{tenantId}/memberships/{subjectId}/suspend")
    ResponseEntity<Changed> suspend(@AuthenticationPrincipal AuthenticatedUserPrincipal actor, @PathVariable UUID tenantId, @PathVariable UUID subjectId) {
        return privateResponse(new Changed(memberships.suspend(actor.userId(), tenantId, subjectId, UUID.randomUUID())));
    }
    /** Requests authorized suspension recovery; the application retains termination as terminal. */
    @PostMapping("/administration/tenants/{tenantId}/memberships/{subjectId}/recover")
    ResponseEntity<Changed> recover(@AuthenticationPrincipal AuthenticatedUserPrincipal actor, @PathVariable UUID tenantId, @PathVariable UUID subjectId) {
        return privateResponse(new Changed(memberships.recover(actor.userId(), tenantId, subjectId, UUID.randomUUID())));
    }
    /** Delegates access termination without deleting historical Staff or Customer identity. */
    @PostMapping("/administration/tenants/{tenantId}/memberships/{subjectId}/terminate")
    ResponseEntity<Changed> terminate(@AuthenticationPrincipal AuthenticatedUserPrincipal actor, @PathVariable UUID tenantId, @PathVariable UUID subjectId) {
        return privateResponse(new Changed(memberships.terminate(actor.userId(), tenantId, subjectId, UUID.randomUUID())));
    }
    /** Maps authorized fingerprint conflict without pretending replay can recover a credential. */
    private static ResponseEntity<StaffProvisioningIssuance> staffResponse(StaffProvisioningIssuance value) {
        if (value instanceof StaffProvisioningIssuance.FingerprintConflict) { throw new ResponseStatusException(HttpStatus.CONFLICT); }
        return privateResponse(value);
    }
    /** Prevents caching of account lifecycle results, including one-time credentials. */
    private static <T> ResponseEntity<T> privateResponse(T body) { return ResponseEntity.ok().header("Cache-Control", "no-store").body(body); }
    public record Operation(UUID operationId, UUID correlationId) {
        /** Requires explicit replay and attribution selectors before entering the lifecycle workflow. */
        public Operation { Objects.requireNonNull(operationId); Objects.requireNonNull(correlationId); }
    }
    public record StaffRequest(UUID departmentId, UUID positionId, String initialRoleCode, UUID operationId, UUID correlationId) {
        /** Requires placement and retry selectors while leaving optional-role authorization to Workforce. */
        public StaffRequest { Objects.requireNonNull(departmentId); Objects.requireNonNull(positionId); Objects.requireNonNull(operationId); Objects.requireNonNull(correlationId); }
    }
    public record Changed(boolean changed) {}
}

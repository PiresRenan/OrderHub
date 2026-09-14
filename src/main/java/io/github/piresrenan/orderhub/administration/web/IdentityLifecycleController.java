package io.github.piresrenan.orderhub.administration.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.ArraySchema;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

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
@Tag(name = "Identity and Tenant lifecycle")
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
    @io.swagger.v3.oas.annotations.Operation(operationId = "identityIssueExternalLinkProof", summary = "Issue an external identity linking proof",
            description = "Requires the bearer identity to resolve to the current internal User. Client operationId durably identifies issuance and correlationId attributes it. The first response returns the credential once; a matching replay returns only the identifier, never a recoverable credential. After a lost initial response, cancel the old proof and issue with a new operationId. Proof lifetime is 15 minutes. Consume using the independently verified new external identity. Successful responses carry Cache-Control: no-store.")
    @ApiResponse(responseCode = "200", description = "Issue an external identity linking proof succeeded",
            content = @Content(mediaType = "application/json", schema = @Schema(name = "ExternalIdentityProofResult", oneOf = {ExternalIdentityLinkIssuance.Issued.class, ExternalIdentityLinkIssuance.Replay.class}, description = "Issued returns a one-time credential and expiry; replay returns only the durable identifier. There is no discriminator property.")))
    @PostMapping("/identity/external-link-proofs")
    ResponseEntity<ExternalIdentityLinkIssuance> issueLink(@Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUserPrincipal actor, @RequestBody Operation request) {
        return privateResponse(identities.issue(actor.userId(), request.operationId(), request.correlationId()));
    }
    /** Cancels a proof scoped to the authenticated owner with server-generated attribution. */
    @io.swagger.v3.oas.annotations.Operation(operationId = "identityCancelExternalLinkProof", summary = "Cancel an external identity proof",
            description = "Requires the bearer identity to resolve to the current internal User. Owner-scoped cancellation uses an opaque proof selector and a server-generated correlation UUID. Successful responses carry Cache-Control: no-store.")
    @ApiResponse(responseCode = "200", description = "Cancel an external identity proof succeeded",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = Changed.class)))
    @DeleteMapping("/identity/external-link-proofs/{proofId}")
    ResponseEntity<Changed> cancelLink(@Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUserPrincipal actor, @Parameter(description = "Opaque proof UUID scoped to the current owner or authorized Tenant", schema = @Schema(type = "string", format = "uuid")) @PathVariable UUID proofId) {
        return privateResponse(new Changed(identities.cancel(actor.userId(), proofId, UUID.randomUUID())));
    }
    /** Returns only the authenticated account binding projection, without provider subjects. */
    @io.swagger.v3.oas.annotations.Operation(operationId = "identityListExternalAccounts", summary = "List current external identity accounts",
            description = "Requires the bearer identity to resolve to the current internal User. Returns the current User's binding IDs and issuer names; provider subjects are omitted. This is an unpaged array. Successful responses carry Cache-Control: no-store.")
    @ApiResponse(responseCode = "200", description = "List current external identity accounts succeeded",
            content = @Content(mediaType = "application/json", array = @ArraySchema(schema = @Schema(implementation = ExternalIdentityAccount.class))))
    @GetMapping("/identity/external-accounts")
    ResponseEntity<List<ExternalIdentityAccount>> accounts(@Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUserPrincipal actor) {
        return privateResponse(identities.accounts(actor.userId()));
    }
    /** Requests owner-scoped revocation while the application enforces last-path safety. */
    @io.swagger.v3.oas.annotations.Operation(operationId = "identityUnlinkExternalAccount", summary = "Unlink an external identity account",
            description = "Requires the bearer identity to resolve to the current internal User. Preserves historical ownership and prevents removing the last active binding at a currently configured trusted issuer. Add and verify the replacement identity before unlinking the old binding. Successful responses carry Cache-Control: no-store.")
    @ApiResponse(responseCode = "200", description = "Unlink an external identity account succeeded",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = Changed.class)))
    @DeleteMapping("/identity/external-accounts/{bindingId}")
    ResponseEntity<Changed> unlink(@Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUserPrincipal actor, @Parameter(description = "Opaque external identity binding UUID belonging to the current User", schema = @Schema(type = "string", format = "uuid")) @PathVariable UUID bindingId) {
        return privateResponse(new Changed(identities.unlink(actor.userId(), bindingId, UUID.randomUUID())));
    }
    /** Derives the issuer from authentication and delegates all Tenant and role policy to Workforce. */
    @io.swagger.v3.oas.annotations.Operation(operationId = "identityIssueStaffProvisioning", summary = "Issue a Staff provisioning intent",
            description = "Requires a bearer-bound internal User, active Tenant/membership and current TENANT_MEMBERS_MANAGE Staff authority within the workforce ceiling. Client operationId durably identifies issuance and correlationId attributes it. The first response returns the credential once; a matching replay returns only the identifier, never a recoverable credential. After a lost initial response, cancel the old proof and issue with a new operationId. Optional initialRoleCode additionally requires role-assignment/delegation authority, with independent privileged-role permission where applicable. Proof lifetime uses the configured Staff TTL (default 30 minutes). Successful responses carry Cache-Control: no-store.")
    @ApiResponse(responseCode = "200", description = "Issue a Staff provisioning intent succeeded",
            content = @Content(mediaType = "application/json", schema = @Schema(name = "StaffProvisioningResult", oneOf = {StaffProvisioningIssuance.Issued.class, StaffProvisioningIssuance.Replay.class}, description = "Issued returns a one-time credential and expiry; replay returns only the durable identifier. There is no discriminator property.")))
    @ApiResponse(responseCode = "409", description = "Operation identity has a different fingerprint or current Staff state conflicts", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    @PostMapping("/administration/tenants/{tenantId}/staff-provisioning")
    ResponseEntity<StaffProvisioningIssuance> issueStaff(@Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUserPrincipal actor,
            @Parameter(description = "Tenant UUID selector; owner application policy establishes authority independently of this path", schema = @Schema(type = "string", format = "uuid")) @PathVariable UUID tenantId, @RequestBody StaffRequest request) {
        return staffResponse(staff.issue(new IssueStaffProvisioningIntentCommand(tenantId, actor.userId(), request.departmentId(),
                request.positionId(), request.initialRoleCode(), request.operationId(), request.correlationId())));
    }
    /** Uses an opaque Tenant intent selector, never a credential, for authorized cancellation. */
    @io.swagger.v3.oas.annotations.Operation(operationId = "identityCancelStaffProvisioning", summary = "Cancel a Staff provisioning intent",
            description = "Requires a bearer-bound internal User, active Tenant/membership and current TENANT_MEMBERS_MANAGE Staff authority within the workforce ceiling. Cancellation selects the Tenant-owned intent UUID; current policy remains necessary, including after original issuance. Successful responses carry Cache-Control: no-store.")
    @ApiResponse(responseCode = "200", description = "Cancel a Staff provisioning intent succeeded",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = Changed.class)))
    @DeleteMapping("/administration/tenants/{tenantId}/staff-provisioning/{intentId}")
    ResponseEntity<Changed> cancelStaff(@Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUserPrincipal actor, @Parameter(description = "Tenant UUID selector; owner application policy establishes authority independently of this path", schema = @Schema(type = "string", format = "uuid")) @PathVariable UUID tenantId, @Parameter(description = "Opaque provisioning intent UUID; never the one-time credential", schema = @Schema(type = "string", format = "uuid")) @PathVariable UUID intentId) {
        return privateResponse(new Changed(staff.cancel(actor.userId(), tenantId, intentId, UUID.randomUUID())));
    }
    /** Invokes only the explicit Platform first-Staff ceremony, not normal Tenant administration. */
    @io.swagger.v3.oas.annotations.Operation(operationId = "identityIssueInitialStaffProvisioning", summary = "Issue initial Tenant Staff provisioning",
            description = "Requires explicit PLATFORM_TENANTS_MANAGE; any historical Staff or completed ceremony closes cold start. The proof establishes the fixed initial Tenant governance placement and role, not general Platform authority. Client operationId durably identifies issuance and correlationId attributes it. The first response returns the credential once; a matching replay returns only the identifier, never a recoverable credential. After a lost initial response, cancel the old proof and issue with a new operationId.  Successful responses carry Cache-Control: no-store.")
    @ApiResponse(responseCode = "200", description = "Issue initial Tenant Staff provisioning succeeded",
            content = @Content(mediaType = "application/json", schema = @Schema(name = "StaffProvisioningResult", oneOf = {StaffProvisioningIssuance.Issued.class, StaffProvisioningIssuance.Replay.class}, description = "Issued returns a one-time credential and expiry; replay returns only the durable identifier. There is no discriminator property.")))
    @ApiResponse(responseCode = "409", description = "Operation fingerprint or current cold-start state conflicts", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    @PostMapping("/administration/tenants/{tenantId}/initial-staff-provisioning")
    ResponseEntity<StaffProvisioningIssuance> issueInitialStaff(@Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUserPrincipal actor,
            @Parameter(description = "Tenant UUID selector; owner application policy establishes authority independently of this path", schema = @Schema(type = "string", format = "uuid")) @PathVariable UUID tenantId, @RequestBody Operation request) {
        return staffResponse(cold.issue(actor.userId(), tenantId, request.operationId(), request.correlationId()));
    }
    /** Delegates bounded Platform cleanup while cold-start eligibility still holds. */
    @io.swagger.v3.oas.annotations.Operation(operationId = "identityCancelInitialStaffProvisioning", summary = "Cancel initial Tenant Staff provisioning",
            description = "Requires explicit PLATFORM_TENANTS_MANAGE; any historical Staff or completed ceremony closes cold start. The proof establishes the fixed initial Tenant governance placement and role, not general Platform authority. Platform cleanup is available only while the cold-start eligibility boundary remains open. Successful responses carry Cache-Control: no-store.")
    @ApiResponse(responseCode = "200", description = "Cancel initial Tenant Staff provisioning succeeded",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = Changed.class)))
    @DeleteMapping("/administration/tenants/{tenantId}/initial-staff-provisioning/{intentId}")
    ResponseEntity<Changed> cancelInitialStaff(@Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUserPrincipal actor, @Parameter(description = "Tenant UUID selector; owner application policy establishes authority independently of this path", schema = @Schema(type = "string", format = "uuid")) @PathVariable UUID tenantId, @Parameter(description = "Opaque provisioning intent UUID; never the one-time credential", schema = @Schema(type = "string", format = "uuid")) @PathVariable UUID intentId) {
        return privateResponse(new Changed(cold.cancel(actor.userId(), tenantId, intentId, UUID.randomUUID())));
    }
    /** Delegates sensitive Customer selection only through the current authorized manager workflow. */
    @io.swagger.v3.oas.annotations.Operation(operationId = "identityIssueCustomerAccountProof", summary = "Issue a Customer account linking proof",
            description = "Requires a bearer-bound internal User, active Tenant/membership and current TENANT_MEMBERS_MANAGE Staff authority within the workforce ceiling. Client operationId durably identifies issuance and correlationId attributes it. The first response returns the credential once; a matching replay returns only the identifier, never a recoverable credential. After a lost initial response, cancel the old proof and issue with a new operationId. The proof selects exactly this Customer; it lives for 15 minutes and never grants Staff authority. Successful responses carry Cache-Control: no-store.")
    @ApiResponse(responseCode = "200", description = "Issue a Customer account linking proof succeeded",
            content = @Content(mediaType = "application/json", schema = @Schema(name = "CustomerAccountProofResult", oneOf = {CustomerLinkIssuance.Issued.class, CustomerLinkIssuance.Replay.class}, description = "Issued returns a one-time credential and expiry; replay returns only the durable identifier. There is no discriminator property.")))
    @PostMapping("/administration/tenants/{tenantId}/customers/{customerId}/account-link-proofs")
    ResponseEntity<CustomerLinkIssuance> issueCustomer(@Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUserPrincipal actor,
            @Parameter(description = "Tenant UUID selector; owner application policy establishes authority independently of this path", schema = @Schema(type = "string", format = "uuid")) @PathVariable UUID tenantId, @Parameter(description = "Tenant Customer UUID selector; possession of this ID is not account ownership proof", schema = @Schema(type = "string", format = "uuid")) @PathVariable UUID customerId, @RequestBody Operation request) {
        return privateResponse(customers.issue(actor.userId(), tenantId, customerId, request.operationId(), request.correlationId()));
    }
    /** Cancels a Tenant-scoped Customer proof through its owner policy and atomic evidence. */
    @io.swagger.v3.oas.annotations.Operation(operationId = "identityCancelCustomerAccountProof", summary = "Cancel a Customer account linking proof",
            description = "Requires a bearer-bound internal User, active Tenant/membership and current TENANT_MEMBERS_MANAGE Staff authority within the workforce ceiling. Selects an opaque Tenant-scoped proof UUID; does not unlink established Customer relationships. Successful responses carry Cache-Control: no-store.")
    @ApiResponse(responseCode = "200", description = "Cancel a Customer account linking proof succeeded",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = Changed.class)))
    @DeleteMapping("/administration/tenants/{tenantId}/customer-account-link-proofs/{proofId}")
    ResponseEntity<Changed> cancelCustomer(@Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUserPrincipal actor, @Parameter(description = "Tenant UUID selector; owner application policy establishes authority independently of this path", schema = @Schema(type = "string", format = "uuid")) @PathVariable UUID tenantId, @Parameter(description = "Opaque proof UUID scoped to the current owner or authorized Tenant", schema = @Schema(type = "string", format = "uuid")) @PathVariable UUID proofId) {
        return privateResponse(new Changed(customers.cancel(actor.userId(), tenantId, proofId, UUID.randomUUID())));
    }
    /** Uses the bound User and proof-selected Customer without accepting caller ownership assertions. */
    @io.swagger.v3.oas.annotations.Operation(operationId = "identityConsumeCustomerAccountProof", summary = "Link the current User to a Customer",
            description = "Requires the bearer identity to resolve to the current internal User. The one-time proof, not a body Customer UUID or provider claim, proves the selected Customer relationship. Creates only the exact binding and active membership desired state; suspended or terminated membership is never reactivated. Concurrent/repeated proof use is unavailable. Successful responses carry Cache-Control: no-store.")
    @ApiResponse(responseCode = "200", description = "Link the current User to a Customer succeeded",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = IdentityBootstrapController.EstablishedIdentity.class)))
    @PostMapping("/tenants/{tenantId}/customer-account-links")
    ResponseEntity<IdentityBootstrapController.EstablishedIdentity> consumeCustomer(@Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUserPrincipal actor,
            @Parameter(description = "Tenant UUID selector; owner application policy establishes authority independently of this path", schema = @Schema(type = "string", format = "uuid")) @PathVariable UUID tenantId, @RequestBody IdentityBootstrapController.Proof request) {
        return privateResponse(new IdentityBootstrapController.EstablishedIdentity(customers.consume(actor.userId(), tenantId, request.credential())));
    }
    /** Delegates explicit membership suspension using the authenticated management actor. */
    @io.swagger.v3.oas.annotations.Operation(operationId = "identitySuspendMembership", summary = "Suspend Tenant membership",
            description = "Requires a bearer-bound internal User, active Tenant/membership and current TENANT_MEMBERS_MANAGE Staff authority within the workforce ceiling. Target Staff must fit the actor ceiling; self transitions are denied. Already-desired state returns changed=false. History is retained. New trust establishment observes the committed transition; requests already in flight are not cancelled. Supports ACTIVE to SUSPENDED; no Staff, Customer or role records are deleted. Successful responses carry Cache-Control: no-store.")
    @ApiResponse(responseCode = "200", description = "Suspend Tenant membership succeeded",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = Changed.class)))
    @PostMapping("/administration/tenants/{tenantId}/memberships/{subjectId}/suspend")
    ResponseEntity<Changed> suspend(@Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUserPrincipal actor, @Parameter(description = "Tenant UUID selector; owner application policy establishes authority independently of this path", schema = @Schema(type = "string", format = "uuid")) @PathVariable UUID tenantId, @Parameter(description = "Target internal User UUID; self membership transitions are denied", schema = @Schema(type = "string", format = "uuid")) @PathVariable UUID subjectId) {
        return privateResponse(new Changed(memberships.suspend(actor.userId(), tenantId, subjectId, UUID.randomUUID())));
    }
    /** Requests authorized suspension recovery; the application retains termination as terminal. */
    @io.swagger.v3.oas.annotations.Operation(operationId = "identityRecoverMembership", summary = "Recover suspended Tenant membership",
            description = "Requires a bearer-bound internal User, active Tenant/membership and current TENANT_MEMBERS_MANAGE Staff authority within the workforce ceiling. Target Staff must fit the actor ceiling; self transitions are denied. Already-desired state returns changed=false. History is retained. New trust establishment observes the committed transition; requests already in flight are not cancelled. Only SUSPENDED membership may recover to ACTIVE; TERMINATED is terminal. Successful responses carry Cache-Control: no-store.")
    @ApiResponse(responseCode = "200", description = "Recover suspended Tenant membership succeeded",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = Changed.class)))
    @PostMapping("/administration/tenants/{tenantId}/memberships/{subjectId}/recover")
    ResponseEntity<Changed> recover(@Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUserPrincipal actor, @Parameter(description = "Tenant UUID selector; owner application policy establishes authority independently of this path", schema = @Schema(type = "string", format = "uuid")) @PathVariable UUID tenantId, @Parameter(description = "Target internal User UUID; self membership transitions are denied", schema = @Schema(type = "string", format = "uuid")) @PathVariable UUID subjectId) {
        return privateResponse(new Changed(memberships.recover(actor.userId(), tenantId, subjectId, UUID.randomUUID())));
    }
    /** Delegates access termination without deleting historical Staff or Customer identity. */
    @io.swagger.v3.oas.annotations.Operation(operationId = "identityTerminateMembership", summary = "Terminate Tenant membership",
            description = "Requires a bearer-bound internal User, active Tenant/membership and current TENANT_MEMBERS_MANAGE Staff authority within the workforce ceiling. Target Staff must fit the actor ceiling; self transitions are denied. Already-desired state returns changed=false. History is retained. New trust establishment observes the committed transition; requests already in flight are not cancelled. ACTIVE or SUSPENDED may become TERMINATED; termination cannot be reversed through this API. Successful responses carry Cache-Control: no-store.")
    @ApiResponse(responseCode = "200", description = "Terminate Tenant membership succeeded",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = Changed.class)))
    @PostMapping("/administration/tenants/{tenantId}/memberships/{subjectId}/terminate")
    ResponseEntity<Changed> terminate(@Parameter(hidden = true) @AuthenticationPrincipal AuthenticatedUserPrincipal actor, @Parameter(description = "Tenant UUID selector; owner application policy establishes authority independently of this path", schema = @Schema(type = "string", format = "uuid")) @PathVariable UUID tenantId, @Parameter(description = "Target internal User UUID; self membership transitions are denied", schema = @Schema(type = "string", format = "uuid")) @PathVariable UUID subjectId) {
        return privateResponse(new Changed(memberships.terminate(actor.userId(), tenantId, subjectId, UUID.randomUUID())));
    }
    /** Maps authorized fingerprint conflict without pretending replay can recover a credential. */
    private static ResponseEntity<StaffProvisioningIssuance> staffResponse(StaffProvisioningIssuance value) {
        if (value instanceof StaffProvisioningIssuance.FingerprintConflict) { throw new ResponseStatusException(HttpStatus.CONFLICT); }
        return privateResponse(value);
    }
    /** Prevents caching of account lifecycle results, including one-time credentials. */
    private static <T> ResponseEntity<T> privateResponse(T body) { return ResponseEntity.ok().header("Cache-Control", "no-store").body(body); }
    @Schema(name = "IdentityOperation", description = "Explicit durable issuance identity and caller attribution; additional fields are rejected")
    public record Operation(
            @Schema(type = "string", format = "uuid", requiredMode = Schema.RequiredMode.REQUIRED, description = "Client-generated durable issuance UUID; preserve for a matching retry") UUID operationId,
            @Schema(type = "string", format = "uuid", requiredMode = Schema.RequiredMode.REQUIRED, description = "Client-generated correlation UUID for this issuance operation") UUID correlationId) {
        /** Requires explicit replay and attribution selectors before entering the lifecycle workflow. */
        public Operation { Objects.requireNonNull(operationId); Objects.requireNonNull(correlationId); }
    }
    @Schema(name = "StaffProvisioningRequest", description = "Requested workforce placement; current actor authority and role delegation remain authoritative")
    public record StaffRequest(
            @Schema(type = "string", format = "uuid", requiredMode = Schema.RequiredMode.REQUIRED, description = "Department UUID in the selected Tenant") UUID departmentId,
            @Schema(type = "string", format = "uuid", requiredMode = Schema.RequiredMode.REQUIRED, description = "Job position UUID in the selected Tenant and permitted workforce ceiling") UUID positionId,
            @Schema(description = "Optional existing Tenant Staff role code; role assignment and privileged delegation checks apply", pattern = "^(?:[A-Z][A-Z0-9_]{2,63})$", minLength = 3, maxLength = 64, requiredMode = Schema.RequiredMode.NOT_REQUIRED, nullable = true) String initialRoleCode,
            @Schema(type = "string", format = "uuid", requiredMode = Schema.RequiredMode.REQUIRED, description = "Client-generated durable issuance UUID; changed frozen intent conflicts") UUID operationId,
            @Schema(type = "string", format = "uuid", requiredMode = Schema.RequiredMode.REQUIRED, description = "Client-generated correlation UUID for this issuance operation") UUID correlationId) {
        /** Requires placement and retry selectors while leaving optional-role authorization to Workforce. */
        public StaffRequest { Objects.requireNonNull(departmentId); Objects.requireNonNull(positionId); Objects.requireNonNull(operationId); Objects.requireNonNull(correlationId); }
    }
    @Schema(name = "IdentityChangeResult")
    public record Changed(@Schema(description = "Whether the requested lifecycle mutation changed state", requiredMode = Schema.RequiredMode.REQUIRED) boolean changed) {}
}

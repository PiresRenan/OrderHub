package io.github.piresrenan.orderhub.administration.web;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.Parameter;
import io.swagger.v3.oas.annotations.media.Content;
import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.responses.ApiResponse;
import io.swagger.v3.oas.annotations.tags.Tag;

import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import io.github.piresrenan.orderhub.security.application.model.VerifiedExternalIdentity;
import io.github.piresrenan.orderhub.users.application.port.in.ExternalIdentityLifecycleUseCase;
import io.github.piresrenan.orderhub.workforce.application.port.in.ConsumeStaffProvisioningUseCase;

/** Thin proof routing; external identity comes exclusively from the verified Security principal. */
@Tag(name = "Identity bootstrap")
@RestController
@RequestMapping(produces = "application/json")
public final class IdentityBootstrapController {
    private final ConsumeStaffProvisioningUseCase staff;
    private final ExternalIdentityLifecycleUseCase identities;
    /** Requires the supplied owner contracts; construction performs no lifecycle mutation or independent commit. */
    public IdentityBootstrapController(ConsumeStaffProvisioningUseCase staff, ExternalIdentityLifecycleUseCase identities) {
        this.staff = staff; this.identities = identities;
    }
    /** Combines the verified external principal with the one-time Staff proof; body claims supply no authority. */
    @Operation(operationId = "identityBootstrapStaff", summary = "Consume Staff provisioning proof",
            description = "Requires a cryptographically verified bearer identity from a configured issuer; an existing internal User binding is not required. The one-time proof selects Tenant, placement and optional role; current issuer authority is revalidated. Consumption atomically establishes or resolves internal identity, active membership and Staff. Unknown, expired, cancelled or consumed proofs are equivalent unavailable outcomes. Unsupported Accept media types are rejected before proof consumption. Successful responses carry Cache-Control: no-store.")
    @ApiResponse(responseCode = "200", description = "Consume Staff provisioning proof succeeded",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = EstablishedIdentity.class)))
    @ApiResponse(responseCode = "409", description = "Existing Staff placement or provisioning state conflicts", content = @Content(mediaType = "application/problem+json", schema = @Schema(implementation = org.springframework.http.ProblemDetail.class)))
    @PostMapping("/identity/bootstrap/staff")
    ResponseEntity<EstablishedIdentity> staff(@Parameter(hidden = true) @AuthenticationPrincipal VerifiedExternalIdentity identity, @RequestBody Proof request) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(new EstablishedIdentity(staff.consume(request.credential(), identity.issuer(), identity.subject())));
    }
    /** Links the independently verified identity to the User selected by the owner-issued proof. */
    @Operation(operationId = "identityBootstrapExternalLink", summary = "Consume external identity linking proof",
            description = "Requires a separately cryptographically verified bearer identity from a configured issuer; no existing internal binding is required. The owner-issued one-time proof selects the stable internal User. An external identity owned by another User is unavailable and never reassigned; a revoked binding can be restored only to its original owner. Repeated or concurrent consumption is unavailable. Unsupported Accept media types are rejected before proof consumption. Successful responses carry Cache-Control: no-store.")
    @ApiResponse(responseCode = "200", description = "Consume external identity linking proof succeeded",
            content = @Content(mediaType = "application/json", schema = @Schema(implementation = EstablishedIdentity.class)))
    @PostMapping("/identity/bootstrap/external-links")
    ResponseEntity<EstablishedIdentity> link(@Parameter(hidden = true) @AuthenticationPrincipal VerifiedExternalIdentity identity, @RequestBody Proof request) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(new EstablishedIdentity(identities.consume(request.credential(), identity.issuer(), identity.subject())));
    }
    @Schema(name = "IdentityOneTimeProof", description = "One-time proof obtained through the corresponding authorized issuance workflow")
    public record Proof(
            @Schema(description = "Canonical unpadded base64url encoding of 32 random bytes; do not log or persist it in clients. Missing, invalid or unusable proofs are rejected without enumeration.", format = "password", minLength = 43, maxLength = 43, pattern = "^(?:[A-Za-z0-9_-]{43})$", accessMode = Schema.AccessMode.WRITE_ONLY, requiredMode = Schema.RequiredMode.REQUIRED) String credential) {
        /** Redacts credential or provider identity data from incidental textual logging. */
        @Override public String toString() { return "Proof[redacted]"; }
    }
    @Schema(name = "EstablishedIdentity", description = "Opaque result identifier: Staff ID for Staff bootstrap, internal User ID for external linking, or Customer ID for Customer linking")
    public record EstablishedIdentity(
            @Schema(description = "Established relationship identifier; interpretation follows the invoked operation", type = "string", format = "uuid", requiredMode = Schema.RequiredMode.REQUIRED) UUID id) {}
}

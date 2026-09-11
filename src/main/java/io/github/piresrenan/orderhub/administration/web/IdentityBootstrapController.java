package io.github.piresrenan.orderhub.administration.web;

import java.util.UUID;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.*;
import io.github.piresrenan.orderhub.security.application.model.VerifiedExternalIdentity;
import io.github.piresrenan.orderhub.users.application.port.in.ExternalIdentityLifecycleUseCase;
import io.github.piresrenan.orderhub.workforce.application.port.in.ConsumeStaffProvisioningUseCase;

/** Thin proof routing; external identity comes exclusively from the verified Security principal. */
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
    @PostMapping("/identity/bootstrap/staff")
    ResponseEntity<EstablishedIdentity> staff(@AuthenticationPrincipal VerifiedExternalIdentity identity, @RequestBody Proof request) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(new EstablishedIdentity(staff.consume(request.credential(), identity.issuer(), identity.subject())));
    }
    /** Links the independently verified identity to the User selected by the owner-issued proof. */
    @PostMapping("/identity/bootstrap/external-links")
    ResponseEntity<EstablishedIdentity> link(@AuthenticationPrincipal VerifiedExternalIdentity identity, @RequestBody Proof request) {
        return ResponseEntity.ok().header("Cache-Control", "no-store").body(new EstablishedIdentity(identities.consume(request.credential(), identity.issuer(), identity.subject())));
    }
    public record Proof(String credential) {
        /** Redacts credential or provider identity data from incidental textual logging. */
        @Override public String toString() { return "Proof[redacted]"; }
    }
    public record EstablishedIdentity(UUID id) {}
}

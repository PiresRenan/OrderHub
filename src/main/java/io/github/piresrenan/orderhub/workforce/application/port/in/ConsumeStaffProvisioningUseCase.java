package io.github.piresrenan.orderhub.workforce.application.port.in;

import java.util.UUID;
import org.springframework.modulith.NamedInterface;

/** Bootstrap-only workforce application contract; provider facts must already be cryptographically verified. */
@NamedInterface("staff-provisioning")
public interface ConsumeStaffProvisioningUseCase {
    /** Consumes one proof and returns the materialized Staff identity after all joined work succeeds. */
    UUID consume(String credential, String verifiedIssuer, String verifiedSubject);
}

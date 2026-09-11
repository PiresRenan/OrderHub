package io.github.piresrenan.orderhub.workforce.application.port.in;

import java.util.UUID;

/** Authorized Tenant administration; actor identity must come from the trusted internal principal. */
@org.springframework.modulith.NamedInterface("membership-administration")
public interface ManageTenantMembershipUseCase {
    /** Explicitly suspends an eligible target through current Tenant management authority. */
    boolean suspend(UUID actor, UUID tenant, UUID subject, UUID correlation);
    /** Requests authorized recovery from suspension; termination is never reversed. */
    boolean recover(UUID actor, UUID tenant, UUID subject, UUID correlation);
    /** Removes future membership eligibility while preserving Staff, Customer and evidence history. */
    boolean terminate(UUID actor, UUID tenant, UUID subject, UUID correlation);
}

package io.github.piresrenan.orderhub.workforce.application.port.in;

import java.util.UUID;

/** Authorized Tenant administration; actor identity must come from the trusted internal principal. */
@org.springframework.modulith.NamedInterface("membership-administration")
public interface ManageTenantMembershipUseCase {
    boolean suspend(UUID actor, UUID tenant, UUID subject, UUID correlation);
    boolean recover(UUID actor, UUID tenant, UUID subject, UUID correlation);
    boolean terminate(UUID actor, UUID tenant, UUID subject, UUID correlation);
}

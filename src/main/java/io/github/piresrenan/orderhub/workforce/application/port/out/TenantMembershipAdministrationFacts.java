package io.github.piresrenan.orderhub.workforce.application.port.out;

import java.util.UUID;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionEnvelope;

public interface TenantMembershipAdministrationFacts {
    /** Serializes lifecycle administrators before revalidation, preventing mutual disable races. */
    void lockTenant(UUID tenant);
    /** Executes inside the kernel's read snapshot; null subject is an actor-only preflight. */
    PermissionEnvelope envelope(UUID actor, UUID tenant, UUID subject);
}

package io.github.piresrenan.orderhub.workforce.application.port.out;

import java.util.Optional;
import java.util.UUID;
import io.github.piresrenan.orderhub.authorization.application.port.in.provisioning.StaffProvisioningActor;
import io.github.piresrenan.orderhub.authorization.application.port.in.provisioning.StaffProvisioningTarget;

/** Workforce-owned locked facts; no caller-supplied position ceiling is trusted. */
public interface StaffProvisioningFactsRepository {
    /** Locks and resolves one current ACTIVE actor and its exact placement ceiling. */
    Optional<StaffProvisioningActor> actor(UUID userId, UUID tenantId);
    /** Locks and resolves a same-Tenant Department/JobPosition and its complete ceiling. */
    Optional<StaffProvisioningTarget> target(UUID tenantId, UUID departmentId, UUID positionId);
}

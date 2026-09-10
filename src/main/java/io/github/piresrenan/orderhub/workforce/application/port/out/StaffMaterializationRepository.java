package io.github.piresrenan.orderhub.workforce.application.port.out;

import java.util.UUID;

/** Workforce-owned desired-state write, used only after provisioning authorization. */
public interface StaffMaterializationRepository {
    /**
     * Creates ACTIVE Staff and its exact placement in the caller's transaction.
     * Returns the existing identity only for an already ACTIVE exact placement.
     * Inactive, missing or different existing placement is a conflict, never repair.
     */
    UUID materialize(UUID tenantId, UUID userId, UUID departmentId, UUID positionId);
}

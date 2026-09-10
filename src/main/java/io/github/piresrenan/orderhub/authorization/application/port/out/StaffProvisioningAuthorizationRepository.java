package io.github.piresrenan.orderhub.authorization.application.port.out;

import java.util.UUID;

/** Authorization-owned stabilization and audit, sharing the outer mutation transaction. */
public interface StaffProvisioningAuthorizationRepository {
    /** Stabilizes actor assignments, overrides and role metadata until the outer transaction ends. */
    void lock(UUID userId, UUID tenantId);

    /** Appends bounded applied/no-change attribution for one initial role assignment. */
    void append(UUID actorUserId, UUID tenantId, UUID targetUserId, String roleCode,
            UUID intentId, UUID correlationId, boolean changed);
}

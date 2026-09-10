package io.github.piresrenan.orderhub.authorization.application.port.in.provisioning;

import java.util.UUID;

/** Platform authority restricted to the workforce-owned, one-time empty-Tenant ceremony. */
public interface ColdStartStaffAuthorizationUseCase {
    void requirePlatformManager(UUID actorUserId);
    ColdStartStaffRole plan(UUID actorUserId, UUID tenantId);
    void assign(UUID actorUserId, UUID tenantId, UUID targetUserId, UUID intentId, UUID correlationId);
}

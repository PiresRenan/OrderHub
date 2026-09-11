package io.github.piresrenan.orderhub.authorization.application.port.in.provisioning;

import java.util.UUID;

/** Platform authority restricted to the workforce-owned, one-time empty-Tenant ceremony. */
public interface ColdStartStaffAuthorizationUseCase {
    /** Requires the current explicit Platform permission, without fabricating Staff authority. */
    void requirePlatformManager(UUID actorUserId);
    /** Validates any existing bootstrap role before returning the fixed v1 permission ceiling. */
    ColdStartStaffRole plan(UUID actorUserId, UUID tenantId);
    /** Assigns the validated initial role and required attribution in the ambient transaction. */
    void assign(UUID actorUserId, UUID tenantId, UUID targetUserId, UUID intentId, UUID correlationId);
}

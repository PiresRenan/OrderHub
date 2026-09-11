package io.github.piresrenan.orderhub.authorization.application.port.in.provisioning;

import java.util.UUID;

/** Narrow authorization-owned policy and initial-role mutation used by workforce. */
public interface StaffProvisioningAuthorizationUseCase {
    /** Requires current member-management authority before sensitive placement lookup. */
    void requireManager(StaffProvisioningActor actor);

    /** Rejects position/role delegation outside the actor's current bounded authority. */
    void requirePlacement(StaffProvisioningActor actor, StaffProvisioningTarget target, String initialRoleCode);

    /** Revalidates delegation, assigns one initial role and appends atomic owner-local evidence. */
    void assign(StaffProvisioningActor actor, StaffProvisioningTarget target, UUID targetUserId,
            String roleCode, UUID intentId, UUID correlationId);
}

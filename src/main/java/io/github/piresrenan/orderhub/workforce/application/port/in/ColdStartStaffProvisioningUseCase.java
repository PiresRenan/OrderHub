package io.github.piresrenan.orderhub.workforce.application.port.in;

import java.util.UUID;
import org.springframework.modulith.NamedInterface;
import io.github.piresrenan.orderhub.workforce.application.model.StaffProvisioningIssuance;

/** Explicit one-time Platform ceremony for a Tenant that has never had Staff. */
@NamedInterface("staff-provisioning")
public interface ColdStartStaffProvisioningUseCase {
    /** Issues the explicit Platform ceremony only while historical Tenant Staff authority is absent. */
    StaffProvisioningIssuance issue(UUID actorUserId, UUID tenantId, UUID operationId, UUID correlationId);
    /** Permits Platform cleanup only while the Tenant remains eligible for the first-Staff ceremony. */
    boolean cancel(UUID actorUserId, UUID tenantId, UUID intentId, UUID correlationId);
}

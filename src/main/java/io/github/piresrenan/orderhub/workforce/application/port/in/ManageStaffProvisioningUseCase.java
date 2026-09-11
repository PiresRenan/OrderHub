package io.github.piresrenan.orderhub.workforce.application.port.in;

import java.util.UUID;
import org.springframework.modulith.NamedInterface;
import io.github.piresrenan.orderhub.workforce.application.model.IssueStaffProvisioningIntentCommand;
import io.github.piresrenan.orderhub.workforce.application.model.StaffProvisioningIssuance;

/** Authorized normal-Tenant issuance and cancellation, accepting trusted internal actor identity. */
@NamedInterface("staff-provisioning")
public interface ManageStaffProvisioningUseCase {
    /** Requires current Tenant Staff management authority and delegation before issuing frozen onboarding facts. */
    StaffProvisioningIssuance issue(IssueStaffProvisioningIntentCommand command);

    /** Authorizes Tenant intent cancellation by opaque identifier without revealing or accepting its secret. */
    boolean cancel(UUID actorUserId, UUID tenantId, UUID intentId, UUID correlationId);
}

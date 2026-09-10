package io.github.piresrenan.orderhub.workforce.application.port.in;

import java.util.UUID;
import org.springframework.modulith.NamedInterface;
import io.github.piresrenan.orderhub.workforce.application.model.IssueStaffProvisioningIntentCommand;
import io.github.piresrenan.orderhub.workforce.application.model.StaffProvisioningIssuance;

/** Authorized normal-Tenant issuance and cancellation, accepting trusted internal actor identity. */
@NamedInterface("staff-provisioning")
public interface ManageStaffProvisioningUseCase {
    StaffProvisioningIssuance issue(IssueStaffProvisioningIntentCommand command);

    boolean cancel(UUID actorUserId, UUID tenantId, UUID intentId, UUID correlationId);
}

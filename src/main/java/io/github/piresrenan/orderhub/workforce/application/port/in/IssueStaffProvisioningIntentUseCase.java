package io.github.piresrenan.orderhub.workforce.application.port.in;

import io.github.piresrenan.orderhub.workforce.application.model.IssueStaffProvisioningIntentCommand;
import io.github.piresrenan.orderhub.workforce.application.model.StaffProvisioningIssuance;

/**
 * Issues one Staff provisioning entitlement.
 */
public interface IssueStaffProvisioningIntentUseCase {

    /**
     * Issues or recognizes one idempotent Staff provisioning operation.
     *
     * @param command caller-authorized issuance facts
     * @return newly issued credential, replay, or fingerprint conflict
     */
    StaffProvisioningIssuance issue(
            IssueStaffProvisioningIntentCommand command);
}

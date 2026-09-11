package io.github.piresrenan.orderhub.workforce.application.port.out;

import java.util.UUID;
import io.github.piresrenan.orderhub.workforce.application.model.ConsumedStaffProvisioningIntent;

/** Owner composition required by one authoritative provisioning transaction. */
public interface StaffProvisioningCompletion {
    /** Revalidates current issuing authority and the frozen placement before identity creation. */
    void authorize(ConsumedStaffProvisioningIntent intent);

    /** Applies any authorized initial role and appends required owner-local evidence. */
    void complete(ConsumedStaffProvisioningIntent intent, UUID userId, UUID staffId);
}

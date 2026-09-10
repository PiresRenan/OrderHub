package io.github.piresrenan.orderhub.workforce.application.service;

import java.util.Objects;
import java.util.UUID;
import io.github.piresrenan.orderhub.workforce.application.model.ConsumedStaffProvisioningIntent;
import io.github.piresrenan.orderhub.workforce.application.port.out.StaffProvisioningCompletion;
import io.github.piresrenan.orderhub.workforce.application.port.out.StaffProvisioningEvidenceRepository;

/** Only immutable explicit issuance evidence selects cold start; a failed normal check never does. */
public final class ColdStartAwareStaffProvisioningCompletion implements StaffProvisioningCompletion {
    private final StaffProvisioningCompletion normal;
    private final ColdStartStaffProvisioningService coldStart;
    private final StaffProvisioningEvidenceRepository evidence;

    public ColdStartAwareStaffProvisioningCompletion(StaffProvisioningCompletion normal, ColdStartStaffProvisioningService coldStart,
            StaffProvisioningEvidenceRepository evidence) {
        this.normal = Objects.requireNonNull(normal, "normal");
        this.coldStart = Objects.requireNonNull(coldStart, "coldStart");
        this.evidence = Objects.requireNonNull(evidence, "evidence");
    }

    @Override public void authorize(ConsumedStaffProvisioningIntent intent) {
        if (evidence.isColdStart(intent.tenantId(), intent.intentId())) { coldStart.authorize(intent); }
        else { normal.authorize(intent); }
    }

    @Override public void complete(ConsumedStaffProvisioningIntent intent, UUID userId, UUID staffId) {
        if (evidence.isColdStart(intent.tenantId(), intent.intentId())) { coldStart.complete(intent, userId, staffId); }
        else { normal.complete(intent, userId, staffId); }
    }
}

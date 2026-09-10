package io.github.piresrenan.orderhub.workforce.application.port.out;

import java.util.UUID;
import io.github.piresrenan.orderhub.workforce.application.model.StaffProvisioningEvidence;

/** Owner-local append-only provisioning attribution, joined to its mutation. */
public interface StaffProvisioningEvidenceRepository {
    /** Appends one successfully applied transition in the caller's physical transaction. */
    void append(StaffProvisioningEvidence evidence);

    /** Resolves an explicitly recorded cold-start issuance mode inside the exact Tenant. */
    boolean isColdStart(UUID tenantId, UUID intentId);
}

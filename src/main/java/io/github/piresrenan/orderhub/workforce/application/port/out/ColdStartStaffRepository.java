package io.github.piresrenan.orderhub.workforce.application.port.out;

import java.util.UUID;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionEnvelope;
import io.github.piresrenan.orderhub.workforce.application.model.ColdStartStaffPlacement;

/** Workforce alone proves and stabilizes the absence of any prior Staff relationship. */
public interface ColdStartStaffRepository {
    void lockEmptyTenant(UUID tenantId);
    ColdStartStaffPlacement prepare(UUID tenantId, PermissionEnvelope envelope);
}

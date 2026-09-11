package io.github.piresrenan.orderhub.workforce.application.port.out;

import java.util.UUID;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionEnvelope;
import io.github.piresrenan.orderhub.workforce.application.model.ColdStartStaffPlacement;

/** Workforce alone proves and stabilizes the absence of any prior Staff relationship. */
public interface ColdStartStaffRepository {
    /** Serializes bootstrap and rejects any historical Staff or completed ceremony before mutation. */
    void lockEmptyTenant(UUID tenantId);
    /** Creates or validates the exact initial placement and permission ceiling, never an implicit broader default. */
    ColdStartStaffPlacement prepare(UUID tenantId, PermissionEnvelope envelope);
}

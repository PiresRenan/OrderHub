package io.github.piresrenan.orderhub.workforce.application.port.out;

import java.util.UUID;

import io.github.piresrenan.orderhub.authorization.domain.model.PermissionEnvelope;

/** Reads current workforce authority while participating in the caller's decision snapshot. */
@FunctionalInterface
public interface WorkforcePermissionEnvelopeRepository {

    /** Returns the exact User/Tenant ceiling, or none for absent/inactive Staff or placement. */
    PermissionEnvelope find(UUID userId, UUID tenantId);
}

package io.github.piresrenan.orderhub.authorization.application.port.in.provisioning;

import java.util.Objects;
import java.util.UUID;
import io.github.piresrenan.orderhub.authorization.domain.model.AuthorityBand;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionEnvelope;

/** Current locked workforce facts; only the workforce owner may supply this input. */
public record StaffProvisioningActor(UUID userId, UUID tenantId, AuthorityBand band, PermissionEnvelope envelope) {
    /** Requires a complete current Staff authority ceiling, never provider claims. */
    public StaffProvisioningActor {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(band, "band");
        Objects.requireNonNull(envelope, "envelope");
    }
}

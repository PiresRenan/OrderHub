package io.github.piresrenan.orderhub.authorization.application.port.in.provisioning;

import java.util.Objects;
import io.github.piresrenan.orderhub.authorization.domain.model.AuthorityBand;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionEnvelope;

/** Current locked target position ceiling supplied by workforce, without foreign entities. */
public record StaffProvisioningTarget(AuthorityBand band, PermissionEnvelope envelope) {
    /** Requires the full target ceiling so overflow can be rejected rather than clipped. */
    public StaffProvisioningTarget {
        Objects.requireNonNull(band, "band");
        Objects.requireNonNull(envelope, "envelope");
    }
}

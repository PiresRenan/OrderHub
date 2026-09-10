package io.github.piresrenan.orderhub.authorization.application.port.in.provisioning;

import java.util.Objects;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionEnvelope;

/** Authorization-owned initial governance role and the exact workforce ceiling it requires. */
public record ColdStartStaffRole(String code, PermissionEnvelope envelope) {
    public ColdStartStaffRole {
        Objects.requireNonNull(code, "code");
        Objects.requireNonNull(envelope, "envelope");
    }
}

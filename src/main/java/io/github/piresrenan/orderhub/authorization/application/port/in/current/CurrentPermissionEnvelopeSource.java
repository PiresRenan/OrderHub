package io.github.piresrenan.orderhub.authorization.application.port.in.current;

import java.util.UUID;

import io.github.piresrenan.orderhub.authorization.domain.model.PermissionEnvelope;

/** Owner-supplied ceiling lookup invoked inside the kernel's coherent decision snapshot. */
@FunctionalInterface
public interface CurrentPermissionEnvelopeSource {

    /** Resolves only the exact internal User/Tenant ceiling; absent eligibility returns none. */
    PermissionEnvelope resolve(UUID userId, UUID tenantId);
}

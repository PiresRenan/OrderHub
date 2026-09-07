package io.github.piresrenan.orderhub.tenants.application.port.out;

import java.util.UUID;

import io.github.piresrenan.orderhub.tenants.domain.model.TenantStatus;

public interface TenantLifecycleRepository {
    TenantLifecycleMutationResult setStatus(UUID tenantId, TenantStatus desiredStatus);
}

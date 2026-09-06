package io.github.piresrenan.orderhub.tenants.application.port.in.administration;

import java.util.UUID;


public interface PlatformTenantUseCase {
    AdministrativeTenant create(UUID actorUserId, String name, UUID correlationId);

    AdministrativeChangeResult suspend(
            UUID actorUserId, UUID tenantId, UUID correlationId);

    AdministrativeChangeResult recover(
            UUID actorUserId, UUID tenantId, UUID correlationId);
}

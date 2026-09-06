package io.github.piresrenan.orderhub.tenants.application.port.in.administration;

import java.util.Optional;
import java.util.UUID;

public interface FindTenantAdministrativeMetadataUseCase {
    Optional<AdministrativeTenant> findById(UUID tenantId);
}

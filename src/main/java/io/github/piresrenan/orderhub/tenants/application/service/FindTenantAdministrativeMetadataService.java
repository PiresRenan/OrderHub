package io.github.piresrenan.orderhub.tenants.application.service;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import io.github.piresrenan.orderhub.tenants.application.port.in.administration.AdministrativeTenant;
import io.github.piresrenan.orderhub.tenants.application.port.in.administration.FindTenantAdministrativeMetadataUseCase;
import io.github.piresrenan.orderhub.tenants.application.port.out.TenantRepository;

public final class FindTenantAdministrativeMetadataService
        implements FindTenantAdministrativeMetadataUseCase {

    private final TenantRepository tenants;

    public FindTenantAdministrativeMetadataService(TenantRepository tenants) {
        this.tenants = Objects.requireNonNull(tenants, "tenants");
    }

    @Override
    public Optional<AdministrativeTenant> findById(UUID tenantId) {
        return tenants.findById(Objects.requireNonNull(tenantId, "tenantId"))
                .map(tenant -> new AdministrativeTenant(
                        tenant.id(), tenant.name(), tenant.status().name()));
    }
}

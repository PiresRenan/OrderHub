package io.github.piresrenan.orderhub.tenants.application.service;

import java.util.Optional;

import io.github.piresrenan.orderhub.tenants.application.port.in.operational.FindTenantOperationalStateQuery;
import io.github.piresrenan.orderhub.tenants.application.port.in.operational.FindTenantOperationalStateUseCase;
import io.github.piresrenan.orderhub.tenants.application.port.in.operational.TenantOperationalState;
import io.github.piresrenan.orderhub.tenants.application.port.in.operational.TenantOperationalStateUnavailableException;
import io.github.piresrenan.orderhub.tenants.application.port.out.TenantPersistenceException;
import io.github.piresrenan.orderhub.tenants.application.port.out.TenantRepository;
import io.github.piresrenan.orderhub.tenants.domain.model.TenantStatus;

public final class FindTenantOperationalStateService
        implements FindTenantOperationalStateUseCase {

    private final TenantRepository tenantRepository;

    public FindTenantOperationalStateService(
            TenantRepository tenantRepository) {

        if (tenantRepository == null) {
            throw new IllegalArgumentException(
                    "Tenant repository is required");
        }

        this.tenantRepository =
                tenantRepository;
    }

    @Override
    public Optional<TenantOperationalState> find(
            FindTenantOperationalStateQuery query) {

        if (query == null) {
            throw new IllegalArgumentException(
                    "Tenant operational-state query is required");
        }

        try {
            return tenantRepository
                    .findById(
                            query.tenantId())
                    .map(tenant ->
                            toOperationalState(
                                    tenant.status()));

        } catch (TenantPersistenceException exception) {

            throw new TenantOperationalStateUnavailableException(
                    exception);
        }
    }

    private static TenantOperationalState toOperationalState(
            TenantStatus status) {

        return switch (status) {
            case ACTIVE ->
                    TenantOperationalState.ACTIVE;

            case SUSPENDED ->
                    TenantOperationalState.SUSPENDED;
        };
    }
}

package io.github.piresrenan.orderhub.tenants.application.service;

import java.util.List;

import io.github.piresrenan.orderhub.tenants.application.port.in.operational.ActiveTenantSummary;
import io.github.piresrenan.orderhub.tenants.application.port.in.operational.FindActiveTenantSummariesQuery;
import io.github.piresrenan.orderhub.tenants.application.port.in.operational.FindActiveTenantSummariesUseCase;
import io.github.piresrenan.orderhub.tenants.application.port.in.operational.TenantOperationalStateUnavailableException;
import io.github.piresrenan.orderhub.tenants.application.port.out.TenantBatchRepository;
import io.github.piresrenan.orderhub.tenants.application.port.out.TenantPersistenceException;
import io.github.piresrenan.orderhub.tenants.domain.model.TenantStatus;

/**
 * Keeps Tenant operational policy inside Tenants while answering a bounded
 * batch question with one persistence read.
 */
public final class FindActiveTenantSummariesService
        implements FindActiveTenantSummariesUseCase {

    private final TenantBatchRepository repository;

    /** Requires the Tenants-owned batch persistence boundary. */
    public FindActiveTenantSummariesService(
            TenantBatchRepository repository) {

        if (repository == null) {
            throw new IllegalArgumentException(
                    "Tenant batch repository is required");
        }

        this.repository = repository;
    }

    /** Skips persistence entirely for an empty batch; otherwise reads once. */
    @Override
    public List<ActiveTenantSummary> find(
            FindActiveTenantSummariesQuery query) {

        if (query == null) {
            throw new IllegalArgumentException(
                    "Tenant summaries query is required");
        }

        if (query.tenantIds().isEmpty()) {
            return List.of();
        }

        try {
            return repository.findAllById(query.tenantIds()).stream()
                    .filter(tenant -> tenant.status() == TenantStatus.ACTIVE)
                    .map(tenant -> new ActiveTenantSummary(tenant.id(), tenant.name()))
                    .toList();

        } catch (TenantPersistenceException exception) {
            throw new TenantOperationalStateUnavailableException(exception);
        }
    }
}

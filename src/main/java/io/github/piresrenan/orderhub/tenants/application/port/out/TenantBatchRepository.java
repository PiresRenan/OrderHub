package io.github.piresrenan.orderhub.tenants.application.port.out;

import java.util.Collection;
import java.util.List;
import java.util.UUID;

import io.github.piresrenan.orderhub.tenants.domain.model.Tenant;

/**
 * Reconstructs a bounded set of Tenant aggregates in one read.
 */
public interface TenantBatchRepository {

    /**
     * Finds the Tenants whose identifiers are supplied; absent identifiers are
     * simply not returned.
     *
     * @param tenantIds bounded identifier batch
     * @return found Tenants, in no guaranteed order
     * @throws TenantPersistenceException when PostgreSQL access fails
     */
    List<Tenant> findAllById(
            Collection<UUID> tenantIds);
}

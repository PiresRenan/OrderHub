package io.github.piresrenan.orderhub.tenants.application.port.out;

import java.util.Optional;
import java.util.UUID;

import io.github.piresrenan.orderhub.tenants.domain.model.Tenant;

public interface TenantRepository {

    /**
     * Persists a complete Tenant aggregate.
     *
     * @param tenant valid aggregate to persist
     * @return persisted Tenant aggregate
     */
    Tenant save(Tenant tenant);

    /**
     * Finds a Tenant by its aggregate identity.
     *
     * @param tenantId Tenant identifier
     * @return Tenant when present, otherwise empty
     */
    Optional<Tenant> findById(UUID tenantId);
}

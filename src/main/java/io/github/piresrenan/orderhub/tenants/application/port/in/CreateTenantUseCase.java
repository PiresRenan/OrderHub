package io.github.piresrenan.orderhub.tenants.application.port.in;

import io.github.piresrenan.orderhub.tenants.domain.model.Tenant;

public interface CreateTenantUseCase {

    /**
     * Executes Tenant creation independently of the adapter that initiated the use
     * case.
     *
     * @param command application input required to create a Tenant
     * @return the successfully created and persisted Tenant aggregate
     * @throws IllegalArgumentException when domain invariants reject the command
     */
    Tenant create(CreateTenantCommand command);
}

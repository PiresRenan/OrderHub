package io.github.piresrenan.orderhub.tenants.application.service;

import io.github.piresrenan.orderhub.tenants.application.port.in.CreateTenantCommand;
import io.github.piresrenan.orderhub.tenants.application.port.in.CreateTenantUseCase;
import io.github.piresrenan.orderhub.tenants.application.port.out.TenantIdGenerator;
import io.github.piresrenan.orderhub.tenants.application.port.out.TenantRepository;
import io.github.piresrenan.orderhub.tenants.domain.model.Tenant;

public final class CreateTenantService implements CreateTenantUseCase {

    private final TenantRepository tenantRepository;
    private final TenantIdGenerator tenantIdGenerator;

    /**
     * Creates the application service using only application-owned output ports.
     *
     * <p>
     * Constructor injection keeps Tenant creation independent of Spring,
     * PostgreSQL and any concrete identity-generation mechanism.
     * </p>
     *
     * @param tenantRepository persistence boundary used after valid aggregate
     *                         creation
     * @param tenantIdGenerator identity-generation boundary for new Tenants
     */
    public CreateTenantService(
            TenantRepository tenantRepository,
            TenantIdGenerator tenantIdGenerator) {

        this.tenantRepository = tenantRepository;
        this.tenantIdGenerator = tenantIdGenerator;
    }

    /**
     * Coordinates identity generation, domain construction and persistence for one
     * new Tenant.
     *
     * <p>
     * Domain construction occurs before persistence so rejected Tenant state never
     * crosses the repository boundary.
     * </p>
     *
     * @param command application input containing the Tenant name
     * @return successfully created and persisted Tenant
     * @throws IllegalArgumentException when Tenant domain invariants reject the
     *                                  supplied state
     */
    @Override
    public Tenant create(CreateTenantCommand command) {
        var tenant = Tenant.create(
                tenantIdGenerator.generate(),
                command.name());

        return tenantRepository.save(tenant);
    }
}

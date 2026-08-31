package io.github.piresrenan.orderhub.users.application.service;

import io.github.piresrenan.orderhub.users.application.port.in.EstablishTenantMembershipCommand;
import io.github.piresrenan.orderhub.users.application.port.in.EstablishTenantMembershipUseCase;
import io.github.piresrenan.orderhub.users.application.port.out.TenantMembershipRepository;
import io.github.piresrenan.orderhub.users.domain.model.TenantMembership;

public final class EstablishTenantMembershipService
        implements EstablishTenantMembershipUseCase {

    private final TenantMembershipRepository tenantMembershipRepository;

    /**
     * Creates the membership application service using its persistence output
     * boundary.
     *
     * @param tenantMembershipRepository persistence boundary for User/Tenant
     *                                   associations
     */
    public EstablishTenantMembershipService(
            TenantMembershipRepository tenantMembershipRepository) {

        this.tenantMembershipRepository = tenantMembershipRepository;
    }

    /**
     * Coordinates domain construction and persistence for one User/Tenant
     * membership.
     *
     * <p>
     * This use case deliberately does not query Tenant internals or infer
     * authorization from membership existence.
     * </p>
     *
     * @param command identities participating in the association
     * @return successfully created and persisted TenantMembership
     * @throws IllegalArgumentException when membership domain invariants reject
     *                                  the supplied identities
     */
    @Override
    public TenantMembership establish(
            EstablishTenantMembershipCommand command) {

        var membership = TenantMembership.create(
                command.userId(),
                command.tenantId());

        return tenantMembershipRepository.save(
                membership);
    }
}

package io.github.piresrenan.orderhub.users.application.service;

import java.util.Optional;

import io.github.piresrenan.orderhub.users.application.port.in.FindTenantMembershipQuery;
import io.github.piresrenan.orderhub.users.application.port.in.FindTenantMembershipUseCase;
import io.github.piresrenan.orderhub.users.application.port.out.TenantMembershipRepository;
import io.github.piresrenan.orderhub.users.domain.model.TenantMembership;

public final class FindTenantMembershipService
        implements FindTenantMembershipUseCase {

    private final TenantMembershipRepository tenantMembershipRepository;

    /**
     * Creates the membership query service using its application-owned
     * persistence boundary.
     *
     * @param tenantMembershipRepository membership lookup output port
     */
    public FindTenantMembershipService(
            TenantMembershipRepository tenantMembershipRepository) {

        this.tenantMembershipRepository = tenantMembershipRepository;
    }

    /**
     * Delegates one validated exact-pair membership lookup to the persistence
     * boundary.
     *
     * @param query complete User/Tenant membership identity
     * @return matching membership when present, otherwise empty
     */
    @Override
    public Optional<TenantMembership> find(
            FindTenantMembershipQuery query) {

        return tenantMembershipRepository.find(
                query.userId(),
                query.tenantId());
    }
}

package io.github.piresrenan.orderhub.users.application.service;

import io.github.piresrenan.orderhub.users.application.port.in.IsTenantMembershipOperationallyActiveQuery;
import io.github.piresrenan.orderhub.users.application.port.in.IsTenantMembershipOperationallyActiveUseCase;
import io.github.piresrenan.orderhub.users.application.port.out.TenantMembershipRepository;
import io.github.piresrenan.orderhub.users.domain.model.TenantMembership;

/**
 * Evaluates membership operational eligibility inside Users.
 *
 * <p>
 * The reconstructed TenantMembership answers the lifecycle question and then
 * stays inside this module. Only the resulting predicate crosses the Users
 * application input boundary.
 * </p>
 */
public final class IsTenantMembershipOperationallyActiveService
        implements IsTenantMembershipOperationallyActiveUseCase {

    private final TenantMembershipRepository tenantMembershipRepository;

    /**
     * Creates the membership eligibility service using its application-owned
     * persistence boundary.
     *
     * @param tenantMembershipRepository membership lookup output port
     */
    public IsTenantMembershipOperationallyActiveService(
            TenantMembershipRepository tenantMembershipRepository) {

        this.tenantMembershipRepository = tenantMembershipRepository;
    }

    /**
     * Resolves the exact membership and lets the Users domain model decide
     * whether it is still operational.
     *
     * @param query complete User/Tenant membership identity
     * @return true only for an existing operational membership
     */
    @Override
    public boolean isOperationallyActive(
            IsTenantMembershipOperationallyActiveQuery query) {

        return tenantMembershipRepository.find(
                        query.userId(),
                        query.tenantId())
                .filter(TenantMembership::isOperationallyActive)
                .isPresent();
    }
}

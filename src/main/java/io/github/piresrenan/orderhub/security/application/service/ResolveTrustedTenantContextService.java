package io.github.piresrenan.orderhub.security.application.service;

import java.util.Optional;

import io.github.piresrenan.orderhub.security.application.model.TrustedTenantContext;
import io.github.piresrenan.orderhub.security.application.port.in.ResolveTrustedTenantContextQuery;
import io.github.piresrenan.orderhub.security.application.port.in.ResolveTrustedTenantContextUseCase;
import io.github.piresrenan.orderhub.tenants.application.port.in.operational.FindTenantOperationalStateQuery;
import io.github.piresrenan.orderhub.tenants.application.port.in.operational.FindTenantOperationalStateUseCase;
import io.github.piresrenan.orderhub.tenants.application.port.in.operational.TenantOperationalState;
import io.github.piresrenan.orderhub.users.application.port.in.FindTenantMembershipQuery;
import io.github.piresrenan.orderhub.users.application.port.in.FindTenantMembershipUseCase;

/**
 * Derives trusted Tenant authority from an authenticated internal User,
 * exact Tenant membership and Tenant operational state.
 */
public final class ResolveTrustedTenantContextService
        implements ResolveTrustedTenantContextUseCase {

    private final FindTenantMembershipUseCase memberships;
    private final FindTenantOperationalStateUseCase tenantOperationalStates;

    public ResolveTrustedTenantContextService(
            FindTenantMembershipUseCase memberships,
            FindTenantOperationalStateUseCase tenantOperationalStates) {

        if (memberships == null) {
            throw new IllegalArgumentException(
                    "Tenant membership boundary is required");
        }

        if (tenantOperationalStates == null) {
            throw new IllegalArgumentException(
                    "Tenant operational-state boundary is required");
        }

        this.memberships =
                memberships;

        this.tenantOperationalStates =
                tenantOperationalStates;
    }

    @Override
    public Optional<TrustedTenantContext> resolve(
            ResolveTrustedTenantContextQuery query) {

        var membershipQuery =
                new FindTenantMembershipQuery(
                        query.authenticatedPrincipal().userId(),
                        query.requestedTenantId());

        if (memberships
                .find(membershipQuery)
                .isEmpty()) {

            return Optional.empty();
        }

        var operationalState =
                tenantOperationalStates.find(
                        new FindTenantOperationalStateQuery(
                                query.requestedTenantId()));

        if (operationalState.isEmpty()) {
            return Optional.empty();
        }

        if (operationalState.orElseThrow()
                != TenantOperationalState.ACTIVE) {

            return Optional.empty();
        }

        return Optional.of(
                new TrustedTenantContext(
                        query.requestedTenantId()));
    }
}

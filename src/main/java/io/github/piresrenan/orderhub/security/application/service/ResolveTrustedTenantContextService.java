package io.github.piresrenan.orderhub.security.application.service;

import java.util.Optional;

import io.github.piresrenan.orderhub.security.application.model.TrustedTenantContext;
import io.github.piresrenan.orderhub.security.application.port.in.ResolveTrustedTenantContextQuery;
import io.github.piresrenan.orderhub.security.application.port.in.ResolveTrustedTenantContextUseCase;
import io.github.piresrenan.orderhub.tenants.application.port.in.operational.FindTenantOperationalStateQuery;
import io.github.piresrenan.orderhub.tenants.application.port.in.operational.FindTenantOperationalStateUseCase;
import io.github.piresrenan.orderhub.tenants.application.port.in.operational.TenantOperationalState;
import io.github.piresrenan.orderhub.users.application.port.in.IsTenantMembershipOperationallyActiveQuery;
import io.github.piresrenan.orderhub.users.application.port.in.IsTenantMembershipOperationallyActiveUseCase;

/**
 * Derives trusted Tenant authority from an authenticated internal User, an
 * operationally active exact Tenant membership and Tenant operational state.
 *
 * <p>
 * Membership lifecycle policy stays inside Users and Tenant lifecycle policy
 * stays inside Tenants. Security composes only the two fail-closed answers.
 * </p>
 */
public final class ResolveTrustedTenantContextService
        implements ResolveTrustedTenantContextUseCase {

    private final IsTenantMembershipOperationallyActiveUseCase memberships;
    private final FindTenantOperationalStateUseCase tenantOperationalStates;

    /** Requires the supplied owner contracts; construction performs no lifecycle mutation or independent commit. */
    public ResolveTrustedTenantContextService(
            IsTenantMembershipOperationallyActiveUseCase memberships,
            FindTenantOperationalStateUseCase tenantOperationalStates) {

        if (memberships == null) {
            throw new IllegalArgumentException(
                    "Tenant membership boundary is required");
        }

        if (tenantOperationalStates == null) {
            throw new IllegalArgumentException(
                    "Tenant operational-state boundary is required");
        }

        this.memberships = memberships;
        this.tenantOperationalStates = tenantOperationalStates;
    }

    /** Requires active membership before Tenant lookup; this point-in-time read does not cancel established requests. */
    @Override
    public Optional<TrustedTenantContext> resolve(
            ResolveTrustedTenantContextQuery query) {

        var membershipQuery =
                new IsTenantMembershipOperationallyActiveQuery(
                        query.authenticatedPrincipal().userId(),
                        query.requestedTenantId());

        if (!memberships.isOperationallyActive(
                membershipQuery)) {

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

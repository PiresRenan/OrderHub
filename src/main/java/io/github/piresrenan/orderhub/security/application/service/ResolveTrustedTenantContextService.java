package io.github.piresrenan.orderhub.security.application.service;

import java.util.Optional;

import io.github.piresrenan.orderhub.security.application.model.TrustedTenantContext;
import io.github.piresrenan.orderhub.security.application.port.in.ResolveTrustedTenantContextQuery;
import io.github.piresrenan.orderhub.security.application.port.in.ResolveTrustedTenantContextUseCase;
import io.github.piresrenan.orderhub.users.application.port.in.FindTenantMembershipQuery;
import io.github.piresrenan.orderhub.users.application.port.in.FindTenantMembershipUseCase;

/**
 * Derives trusted Tenant authority from an authenticated internal User and one
 * untrusted requested Tenant selector.
 *
 * <p>The service delegates membership ownership to the Users application
 * boundary. A requested Tenant becomes trusted only when the exact authenticated
 * User/Tenant association exists.
 */
public final class ResolveTrustedTenantContextService
        implements ResolveTrustedTenantContextUseCase {

    private final FindTenantMembershipUseCase memberships;

    /**
     * Creates the trusted Tenant resolution service.
     *
     * @param memberships Users-owned membership lookup boundary
     * @throws IllegalArgumentException when membership verification is unavailable
     */
    public ResolveTrustedTenantContextService(
            FindTenantMembershipUseCase memberships) {

        if (memberships == null) {
            throw new IllegalArgumentException(
                    "Tenant membership boundary is required");
        }

        this.memberships = memberships;
    }

    /**
     * Resolves trusted Tenant authority for one authenticated request context.
     *
     * <p>The authenticated User UUID is used only to prove membership and is not
     * propagated into the resulting trusted context.
     *
     * @param query authenticated principal and requested Tenant selector
     * @return trusted Tenant context when the exact membership exists, otherwise
     *         empty
     */
    @Override
    public Optional<TrustedTenantContext> resolve(
            ResolveTrustedTenantContextQuery query) {

        var membershipQuery =
                new FindTenantMembershipQuery(
                        query.authenticatedPrincipal().userId(),
                        query.requestedTenantId());

        return memberships
                .find(membershipQuery)
                .map(ignored ->
                        new TrustedTenantContext(
                                query.requestedTenantId()));
    }
}

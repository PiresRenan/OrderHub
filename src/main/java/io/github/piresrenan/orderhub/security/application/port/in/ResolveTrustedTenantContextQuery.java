package io.github.piresrenan.orderhub.security.application.port.in;

import java.util.UUID;

import io.github.piresrenan.orderhub.security.application.model.AuthenticatedUserPrincipal;

/**
 * Carries the complete application input required to derive trusted Tenant
 * context for an authenticated internal User.
 *
 * <p>The requested Tenant identifier remains an untrusted selector at this
 * boundary. This query does not itself establish Tenant authority; membership
 * must still be verified before a TrustedTenantContext can be produced.
 *
 * @param authenticatedPrincipal already authenticated internal User principal
 * @param requestedTenantId Tenant identifier requested by the caller
 */
public record ResolveTrustedTenantContextQuery(
        AuthenticatedUserPrincipal authenticatedPrincipal,
        UUID requestedTenantId) {

    /**
     * Ensures that Tenant-context resolution receives complete authenticated
     * identity and Tenant-selection input.
     *
     * @throws IllegalArgumentException when either required input is missing
     */
    public ResolveTrustedTenantContextQuery {
        if (authenticatedPrincipal == null) {
            throw new IllegalArgumentException(
                    "Authenticated user principal is required");
        }

        if (requestedTenantId == null) {
            throw new IllegalArgumentException(
                    "Requested tenant id is required");
        }
    }
}

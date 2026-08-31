package io.github.piresrenan.orderhub.security.application.port.in;

import java.util.Optional;

import io.github.piresrenan.orderhub.security.application.model.TrustedTenantContext;

/**
 * Resolves whether an authenticated internal User may operate inside one
 * requested Tenant context.
 */
public interface ResolveTrustedTenantContextUseCase {

    /**
     * Verifies the authenticated User/Tenant membership and derives trusted
     * Tenant authority only when that exact association exists.
     *
     * @param query authenticated principal and requested Tenant selector
     * @return trusted Tenant context when membership exists, otherwise empty
     */
    Optional<TrustedTenantContext> resolve(
            ResolveTrustedTenantContextQuery query);
}

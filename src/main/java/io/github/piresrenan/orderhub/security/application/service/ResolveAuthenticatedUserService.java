package io.github.piresrenan.orderhub.security.application.service;

import java.util.Optional;

import io.github.piresrenan.orderhub.security.application.model.AuthenticatedUserPrincipal;
import io.github.piresrenan.orderhub.security.application.port.in.ResolveAuthenticatedUserQuery;
import io.github.piresrenan.orderhub.security.application.port.in.ResolveAuthenticatedUserUseCase;
import io.github.piresrenan.orderhub.users.application.port.in.ResolveExternalIdentityQuery;
import io.github.piresrenan.orderhub.users.application.port.in.ResolveExternalIdentityUseCase;

/**
 * Resolves an external authenticated identity through the Users application
 * contract and projects the result into Security's internal authenticated
 * principal.
 *
 * <p>
 * This service deliberately depends only on Users application contracts. It
 * does not know about Users persistence, PostgreSQL, JWT, Spring Security or
 * HTTP authentication semantics.
 * </p>
 */
public final class ResolveAuthenticatedUserService
        implements ResolveAuthenticatedUserUseCase {

    private final ResolveExternalIdentityUseCase externalIdentityResolver;

    /**
     * Creates the authenticated User resolution service.
     *
     * @param externalIdentityResolver Users-owned external identity resolution
     *        contract
     * @throws IllegalArgumentException when the required Users resolver is absent
     */
    public ResolveAuthenticatedUserService(
            ResolveExternalIdentityUseCase externalIdentityResolver) {

        if (externalIdentityResolver == null) {
            throw new IllegalArgumentException(
                    "External identity resolver is required");
        }

        this.externalIdentityResolver = externalIdentityResolver;
    }

    /**
     * Resolves the provider identity to an internal OrderHub User and removes
     * provider-specific identity data from the resulting principal.
     *
     * @param query exact provider identity to resolve
     * @return authenticated internal principal when mapped, otherwise empty
     */
    @Override
    public Optional<AuthenticatedUserPrincipal> resolve(
            ResolveAuthenticatedUserQuery query) {

        var usersQuery = new ResolveExternalIdentityQuery(
                query.issuer(),
                query.subject());

        return externalIdentityResolver
                .resolve(usersQuery)
                .map(resolved ->
                        new AuthenticatedUserPrincipal(
                                resolved.userId()));
    }
}

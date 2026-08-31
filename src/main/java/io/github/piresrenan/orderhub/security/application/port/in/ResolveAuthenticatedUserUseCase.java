package io.github.piresrenan.orderhub.security.application.port.in;

import java.util.Optional;

import io.github.piresrenan.orderhub.security.application.model.AuthenticatedUserPrincipal;

/**
 * Resolves an externally authenticated identity to OrderHub's internal
 * authenticated User representation.
 */
public interface ResolveAuthenticatedUserUseCase {

    /**
     * Resolves the exact external identity represented by the query.
     *
     * <p>
     * An unmapped external identity is represented as an empty result. Translation
     * of that absence into an authentication protocol response, such as HTTP 401,
     * belongs to an outer authentication adapter.
     * </p>
     *
     * @param query exact external identity to resolve
     * @return authenticated internal principal when the identity is mapped,
     *         otherwise empty
     */
    Optional<AuthenticatedUserPrincipal> resolve(
            ResolveAuthenticatedUserQuery query);
}

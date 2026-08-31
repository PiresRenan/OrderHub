package io.github.piresrenan.orderhub.users.application.port.in;

import java.util.Optional;

/**
 * Defines the framework-neutral application boundary for resolving external
 * provider identity to OrderHub's internal User identity.
 */
public interface ResolveExternalIdentityUseCase {

    /**
     * Resolves one exact external identity to its semantically identified
     * internal User result.
     *
     * <p>
     * Absence is a normal Users application result. Authentication semantics
     * such as HTTP 401 belong to the consuming security boundary.
     * </p>
     *
     * @param query complete external identity lookup
     * @return resolved internal User identity when a binding exists, otherwise
     *         empty
     */
    Optional<ResolvedUserIdentity> resolve(
            ResolveExternalIdentityQuery query);
}

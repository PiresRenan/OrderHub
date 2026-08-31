package io.github.piresrenan.orderhub.security.application.model;

import java.util.UUID;

/**
 * Represents an authenticated OrderHub User after external authentication
 * details have been resolved to the application's internal identity.
 *
 * <p>
 * This principal deliberately contains only the internal User UUID. Provider
 * identifiers, JWT claims, tokens and authentication-framework objects do not
 * belong to this application-level representation.
 * </p>
 *
 * @param userId authenticated internal OrderHub User identifier
 */
public record AuthenticatedUserPrincipal(
        UUID userId) {

    /**
     * Validates the minimum invariant required to represent an authenticated
     * internal User.
     *
     * @throws IllegalArgumentException when the internal User identifier is
     *         absent
     */
    public AuthenticatedUserPrincipal {
        if (userId == null) {
            throw new IllegalArgumentException(
                    "Authenticated user id is required");
        }
    }
}

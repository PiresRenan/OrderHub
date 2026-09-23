package io.github.piresrenan.orderhub.security.application.port.in;

import java.util.UUID;

import io.github.piresrenan.orderhub.security.application.model.AuthenticatedUserPrincipal;

/**
 * Requests one bounded scan window of the authenticated User's selectable
 * Tenant contexts.
 *
 * <p>The User comes only from the authenticated principal. The cursor is
 * pagination control data, not a Tenant selector and not authority.
 *
 * @param authenticatedPrincipal already authenticated internal User principal
 * @param afterId exclusive cursor from a previous page, or null for the first page
 * @param limit scan window size (1-100)
 */
public record DiscoverSelectableTenantsQuery(
        AuthenticatedUserPrincipal authenticatedPrincipal,
        UUID afterId,
        int limit) {

    /** Largest scan window; keeps per-request work bounded. */
    public static final int MAX_LIMIT = 100;

    /**
     * Ensures a complete principal and a bounded window.
     *
     * @throws IllegalArgumentException when the principal is missing or the limit is out of range
     */
    public DiscoverSelectableTenantsQuery {
        if (authenticatedPrincipal == null) {
            throw new IllegalArgumentException(
                    "Authenticated user principal is required");
        }

        if (limit < 1 || limit > MAX_LIMIT) {
            throw new IllegalArgumentException(
                    "Tenant discovery limit must be between 1 and 100");
        }
    }
}

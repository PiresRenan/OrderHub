package io.github.piresrenan.orderhub.users.application.port.in;

import java.util.UUID;

/**
 * Selects one bounded scan window over a User's operationally active
 * memberships, ordered by Tenant identifier.
 *
 * @param userId internal User whose memberships are scanned
 * @param afterTenantId exclusive Tenant identifier cursor, or null for the first window
 * @param windowSize maximum number of membership candidates in the window (1-100)
 */
public record ScanOperationallyActiveMembershipTenantsQuery(
        UUID userId,
        UUID afterTenantId,
        int windowSize) {

    /** Largest window a single scan may read; keeps per-request work bounded. */
    public static final int MAX_WINDOW_SIZE = 100;

    /**
     * Rejects incomplete or unbounded scans before they reach persistence.
     *
     * @throws IllegalArgumentException when the User is missing or the window is out of range
     */
    public ScanOperationallyActiveMembershipTenantsQuery {
        if (userId == null) {
            throw new IllegalArgumentException(
                    "Membership user id is required");
        }

        if (windowSize < 1 || windowSize > MAX_WINDOW_SIZE) {
            throw new IllegalArgumentException(
                    "Membership scan window must be between 1 and 100");
        }
    }
}

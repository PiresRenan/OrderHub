package io.github.piresrenan.orderhub.users.application.port.in;

import java.util.UUID;

/**
 * Represents the stable internal User identity produced by successful external
 * identity resolution.
 *
 * <p>
 * This application contract intentionally exposes only the internal User
 * identifier. External issuer, subject, JWT claims and authentication-provider
 * details must not propagate through this result.
 * </p>
 *
 * @param userId resolved internal OrderHub User identifier
 */
public record ResolvedUserIdentity(
        UUID userId) {

    /**
     * Ensures a successful resolution always carries a usable internal User
     * identity.
     *
     * @throws IllegalArgumentException when the resolved User identifier is
     *                                  missing
     */
    public ResolvedUserIdentity {
        if (userId == null) {
            throw new IllegalArgumentException(
                    "Resolved user id is required");
        }
    }
}

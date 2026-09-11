package io.github.piresrenan.orderhub.users.application.port.in;

import java.util.UUID;

/**
 * Represents the stable internal User identity produced by successful User
 * creation.
 *
 * <p>
 * This application contract intentionally exposes only the internally
 * generated User identifier. The User aggregate and any other Users domain
 * state remain internal to the Users module.
 * </p>
 *
 * @param userId internally generated OrderHub User identifier
 */
public record CreatedUserIdentity(
        UUID userId) {

    /**
     * Ensures a successful creation always carries a usable internal User
     * identity.
     *
     * @throws IllegalArgumentException when the created User identifier is
     *                                  missing
     */
    public CreatedUserIdentity {
        if (userId == null) {
            throw new IllegalArgumentException(
                    "Created user id is required");
        }
    }
}

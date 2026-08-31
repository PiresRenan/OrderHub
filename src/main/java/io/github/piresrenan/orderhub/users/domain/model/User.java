package io.github.piresrenan.orderhub.users.domain.model;

import java.util.UUID;

/**
 * Represents the stable internal identity of an OrderHub user.
 *
 * <p>
 * User identity is deliberately independent of authentication credentials,
 * contact information and external identity-provider identifiers.
 * </p>
 */
public final class User {

    private final UUID id;

    /**
     * Builds a User from identity that has already satisfied the aggregate
     * invariant.
     *
     * @param id immutable internal User identifier
     */
    private User(UUID id) {
        this.id = id;
    }

    /**
     * Creates a new User using an internally meaningful opaque identity.
     *
     * @param id internal User identifier
     * @return valid User aggregate
     * @throws IllegalArgumentException when the identifier is missing
     */
    public static User create(UUID id) {
        validateId(id);

        return new User(id);
    }

    /**
     * Reconstructs an existing User from persisted state while reapplying the
     * domain invariant.
     *
     * @param id persisted internal User identifier
     * @return valid reconstructed User aggregate
     * @throws IllegalArgumentException when persisted identity is invalid
     */
    public static User rehydrate(UUID id) {
        validateId(id);

        return new User(id);
    }

    /**
     * Ensures every User has a stable internal identity.
     *
     * @param id User identifier to validate
     * @throws IllegalArgumentException when the identifier is null
     */
    private static void validateId(UUID id) {
        if (id == null) {
            throw new IllegalArgumentException(
                    "User id is required");
        }
    }

    /**
     * Returns the immutable internal User identity.
     *
     * @return User identifier
     */
    public UUID id() {
        return id;
    }
}

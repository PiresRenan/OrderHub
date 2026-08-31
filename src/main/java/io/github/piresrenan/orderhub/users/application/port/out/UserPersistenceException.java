package io.github.piresrenan.orderhub.users.application.port.out;

/**
 * Represents an infrastructure-independent failure while persisting or
 * retrieving User state.
 *
 * <p>
 * The public message is deliberately stable and does not expose SQL,
 * identifiers, database names or vendor-specific details.
 * </p>
 */
public final class UserPersistenceException extends RuntimeException {

    private static final String MESSAGE =
            "User persistence operation failed.";

    /**
     * Creates a sanitized persistence failure while retaining the original cause
     * for controlled internal diagnostics.
     *
     * @param cause underlying infrastructure failure
     */
    public UserPersistenceException(Throwable cause) {
        super(MESSAGE, cause);
    }
}

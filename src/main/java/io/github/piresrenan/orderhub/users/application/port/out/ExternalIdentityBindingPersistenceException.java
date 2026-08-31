package io.github.piresrenan.orderhub.users.application.port.out;

/**
 * Represents an infrastructure-independent failure while persisting or
 * retrieving ExternalIdentityBinding state.
 *
 * <p>
 * The public message is stable and deliberately excludes issuer, subject,
 * internal User identifiers, SQL, database vendor details and constraint
 * information.
 * </p>
 */
public final class ExternalIdentityBindingPersistenceException
        extends RuntimeException {

    private static final String MESSAGE =
            "External identity binding persistence operation failed.";

    /**
     * Creates a sanitized persistence failure while retaining the original
     * infrastructure cause for controlled internal diagnostics.
     *
     * @param cause underlying persistence failure
     */
    public ExternalIdentityBindingPersistenceException(
            Throwable cause) {

        super(MESSAGE, cause);
    }
}

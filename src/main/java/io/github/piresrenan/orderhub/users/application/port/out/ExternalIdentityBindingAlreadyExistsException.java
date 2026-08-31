package io.github.piresrenan.orderhub.users.application.port.out;

/**
 * Represents an attempt to establish an external identity binding that already
 * exists.
 *
 * <p>
 * The public message deliberately omits issuer, subject, internal User
 * identifier and persistence implementation details.
 * </p>
 */
public final class ExternalIdentityBindingAlreadyExistsException
        extends RuntimeException {

    private static final String MESSAGE =
            "External identity binding already exists.";

    /**
     * Creates the stable application-level duplicate-binding conflict.
     */
    public ExternalIdentityBindingAlreadyExistsException() {
        super(MESSAGE);
    }
}

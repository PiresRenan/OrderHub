package io.github.piresrenan.orderhub.users.application.port.out;

/**
 * Represents an attempt to establish a User/Tenant membership that already
 * exists.
 *
 * <p>
 * The public message deliberately omits User and Tenant identifiers as well as
 * persistence implementation details.
 * </p>
 */
public final class TenantMembershipAlreadyExistsException
        extends RuntimeException {

    private static final String MESSAGE =
            "Tenant membership already exists.";

    /**
     * Creates the stable application-level duplicate-membership conflict.
     */
    public TenantMembershipAlreadyExistsException() {
        super(MESSAGE);
    }
}

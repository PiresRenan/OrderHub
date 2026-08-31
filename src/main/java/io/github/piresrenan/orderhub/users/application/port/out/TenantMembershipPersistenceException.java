package io.github.piresrenan.orderhub.users.application.port.out;

/**
 * Represents an infrastructure-independent failure while persisting or
 * retrieving TenantMembership state.
 *
 * <p>
 * The public message is stable and deliberately excludes SQL, database vendor
 * details and linkable User/Tenant identifiers.
 * </p>
 */
public final class TenantMembershipPersistenceException
        extends RuntimeException {

    private static final String MESSAGE =
            "Tenant membership persistence operation failed.";

    /**
     * Creates a sanitized persistence failure while retaining the original
     * infrastructure cause for controlled internal diagnostics.
     *
     * @param cause underlying persistence failure
     */
    public TenantMembershipPersistenceException(
            Throwable cause) {

        super(MESSAGE, cause);
    }
}

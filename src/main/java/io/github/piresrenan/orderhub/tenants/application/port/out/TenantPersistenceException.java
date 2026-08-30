package io.github.piresrenan.orderhub.tenants.application.port.out;

/**
 * Represents a technical failure while a Tenant repository attempts to complete
 * a persistence operation.
 *
 * <p>
 * The exception belongs to the application output boundary so adapters can
 * translate infrastructure-specific failures without exposing Spring JDBC,
 * driver or database-vendor exception types to application callers.
 * </p>
 */
public final class TenantPersistenceException extends RuntimeException {

    /**
     * Creates a framework-neutral Tenant persistence failure while retaining the
     * original infrastructure cause for internal programmatic inspection.
     *
     * <p>
     * The public message is deliberately stable and contains no Tenant state,
     * identifiers, SQL, connection information or vendor-specific details.
     * </p>
     *
     * @param cause infrastructure exception that prevented the persistence
     *              operation
     */
    public TenantPersistenceException(Throwable cause) {
        super(
                "Tenant persistence operation failed.",
                cause);
    }
}

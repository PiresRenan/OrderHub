package io.github.piresrenan.orderhub.orders.application.port.out;

/**
 * Represents a technical failure while an Order repository attempts to
 * complete a persistence operation.
 *
 * <p>
 * The exception belongs to the application output boundary so persistence
 * adapters can translate framework-specific failures without exposing JDBC,
 * Spring transaction or database-vendor exception types to callers.
 * </p>
 */
public final class OrderPersistenceException extends RuntimeException {

    /**
     * Creates a framework-neutral persistence failure while retaining the
     * original cause for internal programmatic inspection.
     *
     * <p>
     * The public exception message is deliberately stable and contains no
     * identifiers, SQL, connection details or vendor-specific information.
     * </p>
     *
     * @param cause infrastructure exception that prevented persistence
     */
    public OrderPersistenceException(Throwable cause) {
        super("Order persistence operation failed.", cause);
    }
}

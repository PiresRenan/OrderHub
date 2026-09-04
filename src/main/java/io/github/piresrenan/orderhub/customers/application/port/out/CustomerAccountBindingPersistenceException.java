package io.github.piresrenan.orderhub.customers.application.port.out;

/**
 * Represents a technical failure while Customer account-binding persistence
 * attempts to resolve one durable relationship.
 *
 * <p>
 * Infrastructure-specific exception types remain behind the Customers output
 * boundary. The original cause is retained for internal diagnostics while the
 * stable message exposes no SQL, identifiers or database details.
 * </p>
 */
public final class CustomerAccountBindingPersistenceException
        extends RuntimeException {

    public CustomerAccountBindingPersistenceException(
            Throwable cause) {

        super(
                "Customer account-binding persistence operation failed.",
                cause);
    }
}

package io.github.piresrenan.orderhub.inventory.application.port.out;

/**
 * Represents a technical failure while Inventory persistence attempts to
 * complete an operation.
 *
 * <p>
 * Infrastructure-specific exception details remain behind the Inventory
 * output boundary. The original cause is retained for internal diagnostics
 * while the stable public message exposes no SQL, identifiers or database
 * internals.
 * </p>
 */
public final class InventoryPersistenceException
        extends RuntimeException {

    public InventoryPersistenceException(
            Throwable cause) {

        super(
                "Inventory persistence operation failed.",
                cause);
    }
}
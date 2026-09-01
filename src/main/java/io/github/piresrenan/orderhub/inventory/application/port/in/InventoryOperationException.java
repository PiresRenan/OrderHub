package io.github.piresrenan.orderhub.inventory.application.port.in;

/**
 * Stable cross-module representation of an Inventory technical failure.
 *
 * <p>
 * Storage-specific exceptions stay internal to Inventory while the original
 * cause remains available for internal diagnostics.
 * </p>
 */
public final class InventoryOperationException
        extends RuntimeException {

    public InventoryOperationException(
            Throwable cause) {

        super(
                "Inventory operation could not be completed.",
                cause);
    }
}
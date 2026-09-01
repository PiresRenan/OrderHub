package io.github.piresrenan.orderhub.inventory.application.port.in;

/**
 * Indicates that requested inventory demand cannot be accepted.
 *
 * <p>
 * Missing policy, missing position and insufficient physical availability are
 * intentionally indistinguishable outside Inventory.
 * </p>
 */
public final class InventoryCommitmentRejectedException
        extends RuntimeException {

    public InventoryCommitmentRejectedException() {

        super(
                "Inventory commitment could not be accepted.");
    }
}
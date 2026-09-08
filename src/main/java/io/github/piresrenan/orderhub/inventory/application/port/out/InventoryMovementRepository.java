package io.github.piresrenan.orderhub.inventory.application.port.out;

import java.util.Optional;
import io.github.piresrenan.orderhub.inventory.domain.model.InventoryMovement;

/** Durable uniqueness arbitrates retries before stock mutation within one transaction. */
public interface InventoryMovementRepository {
    /** Empty means this transaction inserted the immutable movement; otherwise replay. */
    Optional<InventoryMovement> acquire(InventoryMovement movement, String fingerprint);
    /** Mutates physical stock only, preserving bounds and committed/backordered semantics. */
    void applyStockDelta(InventoryMovement movement);
}

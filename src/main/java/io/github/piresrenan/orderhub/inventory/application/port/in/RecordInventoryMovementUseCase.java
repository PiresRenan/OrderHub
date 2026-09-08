package io.github.piresrenan.orderhub.inventory.application.port.in;

import io.github.piresrenan.orderhub.inventory.domain.model.InventoryMovement;

/** Applies one authorized, durable retry-safe physical-stock command. */
@FunctionalInterface
public interface RecordInventoryMovementUseCase {
    /** Returns the original stable movement on authorized exact replay, without repeating stock effects. */
    InventoryMovement record(InventoryMovementCommand command);
}

package io.github.piresrenan.orderhub.inventory.application.port.in;

/**
 * Aggregate outcome of durably committing all Inventory demand for one Order.
 *
 * <p>
 * This outcome describes allocation only. It is deliberately independent from
 * the lifecycle state of the Order that caused the Inventory commitment.
 * </p>
 */
public enum InventoryAllocationOutcome {

    /** Every accepted unit was physically allocated. */
    FULLY_ALLOCATED,

    /** Some accepted units were allocated and some became backordered demand. */
    PARTIALLY_BACKORDERED,

    /** Every accepted unit became backordered demand. */
    FULLY_BACKORDERED
}
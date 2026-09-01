package io.github.piresrenan.orderhub.orders.application.port.in;

/**
 * Inventory allocation outcome exposed by the create-Order application API.
 *
 * <p>
 * This is intentionally separate from Order lifecycle state. An Order can be
 * CREATED while its accepted demand is fully allocated, partially backordered
 * or fully backordered.
 * </p>
 */
public enum CreateOrderAllocationOutcome {
    FULLY_ALLOCATED,
    PARTIALLY_BACKORDERED,
    FULLY_BACKORDERED
}
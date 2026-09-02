package io.github.piresrenan.orderhub.orders.application.port.in;

/**
 * Identifies whether a successful create-Order call executed new business
 * effects or durably replayed an already completed result.
 */
public enum CreateOrderExecutionKind {

    /**
     * This call acquired the durable idempotency identity and executed Order,
     * Catalog and Inventory work.
     */
    FIRST_EXECUTION,

    /**
     * This call returned an already completed durable outcome without repeating
     * Order, Catalog or Inventory work.
     */
    REPLAY
}

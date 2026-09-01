package io.github.piresrenan.orderhub.orders.application.port.in;

import java.util.Objects;

import io.github.piresrenan.orderhub.orders.domain.model.Order;

/**
 * Successful synchronous result of creating one Order.
 *
 * @param order persisted Order aggregate with its independent business lifecycle
 * @param allocationOutcome Inventory allocation result for the accepted demand
 */
public record CreateOrderResult(
        Order order,
        CreateOrderAllocationOutcome allocationOutcome) {

    public CreateOrderResult {

        Objects.requireNonNull(
                order,
                "order");

        Objects.requireNonNull(
                allocationOutcome,
                "allocationOutcome");
    }
}
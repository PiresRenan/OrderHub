package io.github.piresrenan.orderhub.orders.application.port.out;

import java.util.Objects;
import java.util.UUID;

import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderAllocationOutcome;
import io.github.piresrenan.orderhub.orders.domain.model.OrderStatus;

/**
 * Stable create-Order projection persisted for successful idempotent replay.
 */
public record CreateOrderIdempotencyCompletion(
        UUID orderId,
        OrderStatus orderStatus,
        CreateOrderAllocationOutcome allocationOutcome) {

    public CreateOrderIdempotencyCompletion {
        Objects.requireNonNull(
                orderId,
                "orderId");

        Objects.requireNonNull(
                orderStatus,
                "orderStatus");

        Objects.requireNonNull(
                allocationOutcome,
                "allocationOutcome");
    }
}

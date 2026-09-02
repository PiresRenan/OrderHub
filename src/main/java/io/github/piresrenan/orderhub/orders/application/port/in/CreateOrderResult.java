package io.github.piresrenan.orderhub.orders.application.port.in;

import java.util.Objects;

import io.github.piresrenan.orderhub.orders.domain.model.Order;

/**
 * Successful synchronous result of a create-Order request.
 *
 * <p>
 * New business execution and durable replay intentionally share the same public
 * HTTP representation, but remain distinguishable to application observers so
 * replay traffic cannot be counted as new Order or Inventory throughput.
 * </p>
 *
 * @param order persisted or durably replayed Order aggregate
 * @param allocationOutcome Inventory allocation outcome of the durable creation
 * @param executionKind whether this call executed business effects or replayed
 *        an already completed outcome
 */
public record CreateOrderResult(
        Order order,
        CreateOrderAllocationOutcome allocationOutcome,
        CreateOrderExecutionKind executionKind) {

    /**
     * Compatibility constructor for a successful result produced by a new
     * business execution.
     */
    public CreateOrderResult(
            Order order,
            CreateOrderAllocationOutcome allocationOutcome) {

        this(
                order,
                allocationOutcome,
                CreateOrderExecutionKind.FIRST_EXECUTION);
    }

    public CreateOrderResult {

        Objects.requireNonNull(
                order,
                "order");

        Objects.requireNonNull(
                allocationOutcome,
                "allocationOutcome");

        Objects.requireNonNull(
                executionKind,
                "executionKind");
    }
}

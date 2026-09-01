package io.github.piresrenan.orderhub.orders.adapter.in.web;

import java.util.List;
import java.util.UUID;

import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderAllocationOutcome;
import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderResult;
import io.github.piresrenan.orderhub.orders.domain.model.OrderStatus;

public record OrderResponse(
        UUID id,
        UUID tenantId,
        UUID customerId,
        OrderStatus status,
        CreateOrderAllocationOutcome allocationOutcome,
        List<Item> items) {

    public record Item(
            UUID variantId,
            int quantity) {
    }

    /**
     * Maps the application result to the public HTTP response contract.
     *
     * <p>
     * Order lifecycle and Inventory allocation remain distinct response fields.
     * The explicit mapping also prevents domain objects from becoming accidental
     * serialization contracts.
     * </p>
     *
     * @param result successful create-Order application result
     * @return representation safe for HTTP serialization
     */
    public static OrderResponse from(
            CreateOrderResult result) {

        var order =
                result.order();

        var items =
                order.items()
                        .stream()
                        .map(item ->
                                new Item(
                                        item.variantId(),
                                        item.quantity()))
                        .toList();

        return new OrderResponse(
                order.id(),
                order.tenantId(),
                order.customerId(),
                order.status(),
                result.allocationOutcome(),
                items);
    }
}
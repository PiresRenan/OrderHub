package io.github.piresrenan.orderhub.orders.adapter.in.web;

import java.util.List;
import java.util.UUID;

import io.github.piresrenan.orderhub.orders.domain.model.Order;
import io.github.piresrenan.orderhub.orders.domain.model.OrderStatus;

/**
 * Public representation of persisted Order state returned by Customer
 * self-service reads.
 *
 * <p>
 * Inventory allocation outcome is intentionally absent because it belongs to
 * the create-operation result and is not authoritative persisted Order state.
 * </p>
 */
public record OrderViewResponse(
        UUID id,
        UUID tenantId,
        UUID customerId,
        OrderStatus status,
        List<Item> items) {

    public record Item(
            UUID variantId,
            int quantity) {
    }

    /**
     * Maps authoritative persisted Order state into the read HTTP contract.
     *
     * @param order authorized Order returned by the application boundary
     * @return representation safe for HTTP serialization
     */
    public static OrderViewResponse from(
            Order order) {

        var items =
                order.items()
                        .stream()
                        .map(item ->
                                new Item(
                                        item.variantId(),
                                        item.quantity()))
                        .toList();

        return new OrderViewResponse(
                order.id(),
                order.tenantId(),
                order.customerId(),
                order.status(),
                items);
    }
}

package io.github.piresrenan.orderhub.orders.adapter.in.web;

import java.util.List;
import java.util.UUID;

import io.github.piresrenan.orderhub.orders.domain.model.Order;
import io.github.piresrenan.orderhub.orders.domain.model.OrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;

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
        @Schema(description = "Persisted Order identifier.", format = "uuid", requiredMode = Schema.RequiredMode.REQUIRED)
        UUID id,
        @Schema(description = "Tenant that owns this Order.", format = "uuid", requiredMode = Schema.RequiredMode.REQUIRED)
        UUID tenantId,
        @Schema(description = "Customer that owns this Order.", format = "uuid", requiredMode = Schema.RequiredMode.REQUIRED)
        UUID customerId,
        @Schema(description = "Order lifecycle status.", requiredMode = Schema.RequiredMode.REQUIRED)
        OrderStatus status,
        @Schema(description = "Persisted Order items.", requiredMode = Schema.RequiredMode.REQUIRED)
        List<Item> items) {

    @Schema(name = "OrderViewItem")
    public record Item(
            @Schema(description = "Catalog Variant identifier.", format = "uuid", requiredMode = Schema.RequiredMode.REQUIRED)
            UUID variantId,
            @Schema(description = "Persisted quantity for this Variant.", type = "integer", types = {"integer"}, format = "int32", minimum = "1", maximum = "2147483647", requiredMode = Schema.RequiredMode.REQUIRED)
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

package io.github.piresrenan.orderhub.orders.adapter.in.web;

import java.util.List;
import java.util.UUID;

import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderAllocationOutcome;
import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderResult;
import io.github.piresrenan.orderhub.orders.domain.model.OrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;

public record OrderResponse(
        @Schema(description = "Persisted Order identifier.", format = "uuid", requiredMode = Schema.RequiredMode.REQUIRED)
        UUID id,
        @Schema(description = "Tenant that owns this Order.", format = "uuid", requiredMode = Schema.RequiredMode.REQUIRED)
        UUID tenantId,
        @Schema(description = "Customer that owns this Order.", format = "uuid", requiredMode = Schema.RequiredMode.REQUIRED)
        UUID customerId,
        @Schema(description = "Order lifecycle status.", requiredMode = Schema.RequiredMode.REQUIRED)
        OrderStatus status,
        @Schema(description = "Inventory allocation outcome of this create-Order execution, distinct from Order lifecycle state.", requiredMode = Schema.RequiredMode.REQUIRED)
        CreateOrderAllocationOutcome allocationOutcome,
        @Schema(description = "Accepted Order items.", requiredMode = Schema.RequiredMode.REQUIRED)
        List<Item> items) {

    @Schema(name = "CreatedOrderItem")
    public record Item(
            @Schema(description = "Catalog Variant identifier.", format = "uuid", requiredMode = Schema.RequiredMode.REQUIRED)
            UUID variantId,
            @Schema(description = "Accepted quantity for this Variant.", type = "integer", types = {"integer"}, format = "int32", minimum = "1", maximum = "2147483647", requiredMode = Schema.RequiredMode.REQUIRED)
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
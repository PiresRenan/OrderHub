package io.github.piresrenan.orderhub.orders.adapter.in.web;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

import io.swagger.v3.oas.annotations.media.Schema;
import io.swagger.v3.oas.annotations.media.ArraySchema;

public record CreateOrderRequest(

        @NotNull(message = "customerId is required")
        @Schema(
                description = "Customer identifier that must resolve to an account bound to the authenticated actor.",
                format = "uuid",
                requiredMode = Schema.RequiredMode.REQUIRED)
        UUID customerId,

        @NotEmpty(message = "items must contain at least one item")
        @ArraySchema(minItems = 1, arraySchema = @Schema(description = "Requested items; nonnull elements and the configured technical maximum are enforced", requiredMode = Schema.RequiredMode.REQUIRED), schema = @Schema(implementation = Item.class))
        List<@NotNull(message = "items must not contain null elements") @Valid Item> items) {

    @Schema(name = "CreateOrderItem")
    public record Item(

            @NotNull(message = "variantId is required")
            @Schema(
                    description = "Catalog Variant identifier being ordered.",
                    format = "uuid",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            UUID variantId,

            @NotNull(message = "quantity is required")
            @Positive(message = "quantity must be greater than zero")
            @Schema(
                    description = "Requested quantity for this Variant. Must be greater than zero.",
                    type = "integer", types = {"integer"}, format = "int32", minimum = "1", maximum = "2147483647",
                    requiredMode = Schema.RequiredMode.REQUIRED)
            Integer quantity) {
    }
}
package io.github.piresrenan.orderhub.orders.adapter.in.web;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;

public record CreateOrderRequest(

        @NotNull(message = "customerId is required")
        UUID customerId,

        @NotEmpty(message = "items must contain at least one item")
        List<@NotNull(message = "items must not contain null elements") @Valid Item> items) {

    public record Item(

            @NotNull(message = "variantId is required")
            UUID variantId,

            @NotNull(message = "quantity is required")
            @Positive(message = "quantity must be greater than zero")
            Integer quantity) {
    }
}
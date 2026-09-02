package io.github.piresrenan.orderhub.orders.application.port.in;

import java.util.List;
import java.util.UUID;

public record CreateOrderCommand(
        UUID tenantId,
        UUID customerId,
        List<Item> items,
        CreateOrderIdempotencyKeyDigest idempotencyKeyDigest) {

    public CreateOrderCommand {

        if (idempotencyKeyDigest == null) {
            throw new IllegalArgumentException(
                    "Create-order idempotency key digest is required");
        }
    }

    public record Item(
            UUID variantId,
            int quantity) {
    }
}
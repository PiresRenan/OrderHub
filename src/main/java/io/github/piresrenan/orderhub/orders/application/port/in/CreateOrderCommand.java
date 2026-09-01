package io.github.piresrenan.orderhub.orders.application.port.in;

import java.util.List;
import java.util.UUID;

public record CreateOrderCommand(
        UUID tenantId,
        UUID customerId,
        List<Item> items) {

    public record Item(
            UUID variantId,
            int quantity) {
    }
}
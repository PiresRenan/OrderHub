package io.github.piresrenan.orderhub.orders.application.port.in;

import java.util.UUID;
import java.util.List;

public record CreateOrderCommand (
    UUID tenantId,
    UUID customerId,
    List<Item> items) {
        public record Item (
                            UUID productId,
                            int quantity) {
        }
}
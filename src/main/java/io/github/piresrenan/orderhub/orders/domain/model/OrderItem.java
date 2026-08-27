package io.github.piresrenan.orderhub.orders.domain.model;

import java.util.UUID;

public record OrderItem(
    UUID productId,
    int quantity
) {
    public OrderItem {
        if (productId == null) {
            throw new IllegalArgumentException("O ID do produto não pode estar vazio.");
        }
        if (quantity <= 0) {
            throw new IllegalArgumentException("A quantidade do item deve ser maior que zero.");
        }
    }
}

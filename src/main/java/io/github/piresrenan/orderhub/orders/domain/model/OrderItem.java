package io.github.piresrenan.orderhub.orders.domain.model;

import java.util.UUID;

public record OrderItem(
        UUID variantId,
        int quantity) {

    /**
     * Validates the invariants required for an item to participate in an order.
     *
     * <p>
     * Validation remains inside the domain even when HTTP adapters perform
     * earlier structural validation, ensuring that future adapters cannot create
     * invalid order items by bypassing the web layer.
     * </p>
     *
     * @throws IllegalArgumentException when the variant identifier is absent or
     *                                  the requested quantity is not greater than
     *                                  zero
     */
    public OrderItem {

        if (variantId == null) {
            throw new IllegalArgumentException(
                    "O ID da variante não pode estar vazio.");
        }

        if (quantity <= 0) {
            throw new IllegalArgumentException(
                    "A quantidade do item deve ser maior que zero.");
        }
    }
}
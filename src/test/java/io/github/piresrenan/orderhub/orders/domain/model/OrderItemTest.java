package io.github.piresrenan.orderhub.orders.domain.model;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class OrderItemTest {

    @Test
    void rejectsNonPositiveQuantity() {
        var productId = UUID.randomUUID();

        assertThatThrownBy(() -> new OrderItem(productId, 0))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("A quantidade do item deve ser maior que zero.");

        assertThatThrownBy(() -> new OrderItem(productId, -1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("A quantidade do item deve ser maior que zero.");
    }

    @Test
    void rejectsMissingProductId() {
        assertThatThrownBy(() -> new OrderItem(null, 1))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("O ID do produto não pode estar vazio.");
    }
}

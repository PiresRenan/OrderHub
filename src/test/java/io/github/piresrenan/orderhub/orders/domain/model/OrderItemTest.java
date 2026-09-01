package io.github.piresrenan.orderhub.orders.domain.model;

import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class OrderItemTest {

    @Test
    void rejectsNonPositiveQuantity() {
        // Why: zero or negative quantities have no valid ordering semantics.
        // Covers: domain quantity invariant independently of HTTP validation.
        // Prevents: invalid values affecting future inventory, pricing and totals.
        var variantId = UUID.randomUUID();

        assertThatThrownBy(() -> new OrderItem(variantId, 0))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("A quantidade do item deve ser maior que zero.");

        assertThatThrownBy(() -> new OrderItem(variantId, -1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("A quantidade do item deve ser maior que zero.");
    }

    @Test
    void rejectsMissingProductId() {
        // Why: every item must refer to an identifiable product.
        // Covers: mandatory variant identity at the domain boundary.
        // Prevents: orphan items reaching persistence or downstream integrations.
        assertThatThrownBy(() -> new OrderItem(null, 1))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("O ID da variante não pode estar vazio.");
    }
}

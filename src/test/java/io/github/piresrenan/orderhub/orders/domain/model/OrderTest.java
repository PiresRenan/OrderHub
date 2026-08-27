package io.github.piresrenan.orderhub.orders.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;

class OrderTest {

    @Test
    void createsOrderWithCreatedStatus() {
        var orderId = UUID.randomUUID();
        var tenantId = UUID.randomUUID();
        var customerId = UUID.randomUUID();
        var productId = UUID.randomUUID();

        var item = new OrderItem(productId, 2);

        var order = Order.create(
                                orderId,
                                tenantId,
                                customerId,
                                List.of(item));
        assertThat(order.getId()).isEqualTo(orderId);
        assertThat(order.getTenantId()).isEqualTo(tenantId);
        assertThat(order.getCustomerId()).isEqualTo(customerId);
        assertThat(order.getItems()).containsExactly(item);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.CREATED);
    }

    @Test
    void rejectsOrderWithoutItems() {
        var orderId = UUID.randomUUID();
        var tenantId = UUID.randomUUID();
        var customerId = UUID.randomUUID();

        assertThatThrownBy(() -> Order.create(
            orderId,
            tenantId,
            customerId,
            List.of()))
            .isInstanceOf(IllegalArgumentException.class)
            .hasMessage("O pedido deve conter ao menos um item.");
    }

}

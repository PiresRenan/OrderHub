package io.github.piresrenan.orderhub.orders.adapter.out.persistence.memory;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.List;

import org.junit.jupiter.api.Test;

import io.github.piresrenan.orderhub.orders.domain.model.Order;
import io.github.piresrenan.orderhub.orders.domain.model.OrderItem;

class InMemoryOrderRepositoryTest {
    @Test
    void savesOrder() {
        // Why: the temporary persistence adapter must honor the output-port save
        // contract.
        // Covers: storage invocation and returned aggregate identity.
        // Prevents: adapter implementations that replace, discard or return a different
        // aggregate during persistence.
        var order = Order.create(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                List.of(
                        new OrderItem(
                                UUID.randomUUID(),
                                2)));
        var repository = new InMemoryOrderRepository();
        var savedOrder = repository.save(order);
        assertThat(savedOrder).isSameAs(order);
    }
}

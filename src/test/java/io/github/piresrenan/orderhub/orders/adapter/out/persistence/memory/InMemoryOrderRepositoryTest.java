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

    @Test
    void findsOrderOnlyInsideRequestedTenant() {
        // Why: Order identity is tenant-scoped and a lookup must not expose an
        // aggregate belonging to another tenant.
        // Covers: tenant-aware repository lookup by tenantId and orderId.
        // Prevents: cross-tenant reads caused by looking up an Order only by UUID.

        var tenantA = UUID.randomUUID();
        var tenantB = UUID.randomUUID();
        var orderId = UUID.randomUUID();

        var order = Order.create(
                orderId,
                tenantA,
                UUID.randomUUID(),
                List.of(
                        new OrderItem(
                                UUID.randomUUID(),
                                2)));

        var repository = new InMemoryOrderRepository();
        repository.save(order);

        assertThat(repository.findById(tenantA, orderId))
                .containsSame(order);

        assertThat(repository.findById(tenantB, orderId))
                .isEmpty();
    }

    @Test
    void storesSameOrderIdentifierIndependentlyAcrossTenants() {
        // Why: the same Order UUID is valid in different tenant boundaries.
        // Covers: composite tenant-aware identity in repository storage and lookup.
        // Prevents: one tenant overwriting another tenant's aggregate when their
        // Order UUIDs happen to coincide.

        var tenantA = UUID.randomUUID();
        var tenantB = UUID.randomUUID();
        var sharedOrderId = UUID.randomUUID();

        var orderA = Order.create(
                sharedOrderId,
                tenantA,
                UUID.randomUUID(),
                List.of(
                        new OrderItem(
                                UUID.randomUUID(),
                                1)));

        var orderB = Order.create(
                sharedOrderId,
                tenantB,
                UUID.randomUUID(),
                List.of(
                        new OrderItem(
                                UUID.randomUUID(),
                                3)));

        var repository = new InMemoryOrderRepository();

        repository.save(orderA);
        repository.save(orderB);

        assertThat(repository.findById(tenantA, sharedOrderId))
                .containsSame(orderA);

        assertThat(repository.findById(tenantB, sharedOrderId))
                .containsSame(orderB);
    }
}

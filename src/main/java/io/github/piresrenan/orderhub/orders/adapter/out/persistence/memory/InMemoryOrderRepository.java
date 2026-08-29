package io.github.piresrenan.orderhub.orders.adapter.out.persistence.memory;

import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import io.github.piresrenan.orderhub.orders.application.port.out.OrderRepository;
import io.github.piresrenan.orderhub.orders.domain.model.Order;

public final class InMemoryOrderRepository implements OrderRepository {

    private final Map<OrderKey, Order> orders = new ConcurrentHashMap<>();

    /**
     * Stores the aggregate in process memory using its tenant-scoped persistence
     * identity.
     *
     * <p>This adapter exists to support the initial vertical slice and automated
     * tests. Its data is not durable and disappears when the application process
     * terminates; it must therefore not be treated as production persistence.</p>
     *
     * @param order aggregate to store
     * @return the same stored aggregate
     */
    @Override
    public Order save(Order order) {
        orders.put(
                new OrderKey(
                        order.tenantId(),
                        order.id()),
                order);

        return order;
    }

    /**
     * Finds an aggregate only when both its tenant and Order identifiers match.
     *
     * <p>Using the composite key here mirrors the tenant-scoped identity required
     * by the application contract without introducing any PostgreSQL-specific
     * concept into the adapter.</p>
     *
     * @param tenantId tenant boundary to search
     * @param orderId aggregate identifier to search
     * @return the matching Order when present, otherwise an empty Optional
     */
    @Override
    public Optional<Order> findById(
            UUID tenantId,
            UUID orderId) {

        return Optional.ofNullable(
                orders.get(
                        new OrderKey(
                                tenantId,
                                orderId)));
    }

    /**
     * Represents the complete persistence identity of an Order inside the
     * in-memory adapter.
     *
     * <p>Two Orders may share the same aggregate UUID provided they belong to
     * different tenants.</p>
     *
     * @param tenantId owning tenant identifier
     * @param orderId Order aggregate identifier
     */
    private record OrderKey(
            UUID tenantId,
            UUID orderId) {
    }
}
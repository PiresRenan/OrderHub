package io.github.piresrenan.orderhub.orders.adapter.out.persistence.memory;

import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import io.github.piresrenan.orderhub.orders.application.port.out.OrderRepository;
import io.github.piresrenan.orderhub.orders.domain.model.Order;

public final class InMemoryOrderRepository implements OrderRepository {

    private final Map<UUID, Order> orders = new ConcurrentHashMap<>();

    /**
     * Stores the aggregate in process memory using its order identifier as the key.
     *
     * <p>
     * This adapter exists to support the initial vertical slice and automated
     * tests. Its data is not durable and disappears when the application process
     * terminates; it must therefore not be treated as production persistence.
     * </p>
     *
     * @param order aggregate to store
     * @return the same stored aggregate
     */
    @Override
    public Order save(Order order) {
        orders.put(order.id(), order);
        return order;
    }
}

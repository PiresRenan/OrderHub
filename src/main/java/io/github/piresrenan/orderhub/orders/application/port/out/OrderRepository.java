package io.github.piresrenan.orderhub.orders.application.port.out;

import io.github.piresrenan.orderhub.orders.domain.model.Order;

public interface OrderRepository {
    /**
     * Persists an already validated order aggregate through the configured
     * persistence adapter.
     *
     * <p>
     * The application core defines this contract without depending on storage
     * technology. Implementations may use memory, PostgreSQL or another durable
     * mechanism without changing the use case.
     * </p>
     *
     * @param order valid aggregate to persist
     * @return the persisted aggregate
     */
    Order save(Order order);
}

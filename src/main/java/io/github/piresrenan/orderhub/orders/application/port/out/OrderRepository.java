package io.github.piresrenan.orderhub.orders.application.port.out;

import java.util.Optional;
import java.util.UUID;

import io.github.piresrenan.orderhub.orders.domain.model.Order;

public interface OrderRepository {

    /**
     * Persists an already validated Order aggregate through the configured
     * persistence adapter.
     *
     * <p>The application core defines this contract without depending on storage
     * technology. Implementations may use memory, PostgreSQL or another durable
     * mechanism without changing the use case.</p>
     *
     * @param order valid aggregate to persist
     * @return the persisted aggregate
     * @throws OrderPersistenceException when the configured persistence mechanism
     *         cannot complete the aggregate write
     */
    Order save(Order order);

    /**
     * Finds one Order inside an explicit tenant boundary.
     *
     * <p>The Order identifier alone is not sufficient persistence identity.
     * Implementations must scope the lookup by both tenant and Order identifier
     * so an aggregate owned by another tenant is never returned accidentally.</p>
     *
     * @param tenantId tenant boundary in which the Order must be searched
     * @param orderId Order aggregate identifier
     * @return the matching aggregate when it exists inside the requested tenant,
     *         otherwise an empty Optional
     * @throws OrderPersistenceException when the configured persistence mechanism
     *         cannot complete the lookup
     */
    Optional<Order> findById(
            UUID tenantId,
            UUID orderId);
}

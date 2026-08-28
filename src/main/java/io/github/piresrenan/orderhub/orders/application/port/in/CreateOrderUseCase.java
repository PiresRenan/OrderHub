package io.github.piresrenan.orderhub.orders.application.port.in;

import io.github.piresrenan.orderhub.orders.domain.model.Order;

public interface CreateOrderUseCase {
    /**
     * Executes the order creation use case independently of the adapter that
     * initiated it.
     *
     * <p>
     * HTTP, messaging, batch jobs or future service integrations must depend on
     * this input port instead of the concrete application service.
     * </p>
     *
     * @param command normalized application input required to create an order
     * @return the newly created order aggregate
     */
    Order create(CreateOrderCommand command);
}

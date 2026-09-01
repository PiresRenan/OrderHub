package io.github.piresrenan.orderhub.orders.application.port.in;

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
     * @return persisted Order plus its independent Inventory allocation outcome
     */
    CreateOrderResult create(
            CreateOrderCommand command);
}
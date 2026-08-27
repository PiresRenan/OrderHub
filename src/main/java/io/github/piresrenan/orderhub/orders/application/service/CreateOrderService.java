package io.github.piresrenan.orderhub.orders.application.service;

import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderCommand;
import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderUseCase;
import io.github.piresrenan.orderhub.orders.application.port.out.OrderIdGenerator;
import io.github.piresrenan.orderhub.orders.application.port.out.OrderRepository;
import io.github.piresrenan.orderhub.orders.domain.model.Order;
import io.github.piresrenan.orderhub.orders.domain.model.OrderItem;

public final class CreateOrderService implements CreateOrderUseCase {

    private final OrderRepository orderRepository;
    private final OrderIdGenerator orderIdGenerator;

    /**
     * Creates the application service using only output-port abstractions.
     *
     * <p>
     * Constructor injection keeps the use case independent of Spring and allows
     * persistence and identity generation strategies to be replaced without
     * modifying application logic.
     * </p>
     *
     * @param orderRepository  persistence port used after successful domain
     *                         creation
     * @param orderIdGenerator identity-generation port used for new aggregates
     */
    public CreateOrderService(OrderRepository orderRepository, OrderIdGenerator orderIdGenerator) {
        this.orderRepository = orderRepository;
        this.orderIdGenerator = orderIdGenerator;
    }

    /**
     * Coordinates the creation of a new order.
     *
     * <p>
     * The method translates application input items into domain objects,
     * generates the aggregate identity, delegates invariant enforcement to the
     * domain and persists the aggregate only after successful construction.
     * </p>
     *
     * @param command application input containing tenant, customer and order items
     * @return the successfully created and persisted order
     * @throws IllegalArgumentException when domain invariants reject the supplied
     *                                  command data
     */
    @Override
    public Order create(CreateOrderCommand command) {
        var items = command.items().stream()
                .map(item -> new OrderItem(item.productId(), item.quantity()))
                .toList();
        var order = Order.create(
                orderIdGenerator.generate(),
                command.tenantId(),
                command.customerId(),
                items);
        return orderRepository.save(order);
    }

}

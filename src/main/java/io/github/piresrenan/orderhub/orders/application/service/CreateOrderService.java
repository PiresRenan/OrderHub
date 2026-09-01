package io.github.piresrenan.orderhub.orders.application.service;

import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderCommand;
import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderUseCase;
import io.github.piresrenan.orderhub.orders.application.port.out.OrderIdGenerator;
import io.github.piresrenan.orderhub.orders.application.port.out.OrderRepository;
import io.github.piresrenan.orderhub.orders.application.port.out.TransactionExecutor;
import io.github.piresrenan.orderhub.orders.domain.model.Order;
import io.github.piresrenan.orderhub.orders.domain.model.OrderItem;

/**
 * Coordinates creation of a new Order.
 *
 * <p>
 * Domain validation occurs before a transaction is opened. Once a valid
 * aggregate exists, the application service owns the durable transaction
 * boundary and repositories participate in that caller-owned transaction.
 * </p>
 */
public final class CreateOrderService
        implements CreateOrderUseCase {

    private final OrderRepository orderRepository;
    private final OrderIdGenerator orderIdGenerator;
    private final TransactionExecutor transactionExecutor;

    public CreateOrderService(
            OrderRepository orderRepository,
            OrderIdGenerator orderIdGenerator,
            TransactionExecutor transactionExecutor) {

        this.orderRepository =
                orderRepository;

        this.orderIdGenerator =
                orderIdGenerator;

        this.transactionExecutor =
                transactionExecutor;
    }

    @Override
    public Order create(
            CreateOrderCommand command) {

        var items =
                command.items()
                        .stream()
                        .map(item ->
                                new OrderItem(
                                        item.variantId(),
                                        item.quantity()))
                        .toList();

        var order =
                Order.create(
                        orderIdGenerator.generate(),
                        command.tenantId(),
                        command.customerId(),
                        items);

        return transactionExecutor.execute(
                () ->
                        orderRepository.save(
                                order));
    }
}
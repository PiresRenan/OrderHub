package io.github.piresrenan.orderhub.orders.application.service;

import io.github.piresrenan.orderhub.inventory.application.port.in.CommitOrderInventoryCommand;
import io.github.piresrenan.orderhub.inventory.application.port.in.CommitOrderInventoryUseCase;
import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderCommand;
import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderUseCase;
import io.github.piresrenan.orderhub.orders.application.port.out.OrderIdGenerator;
import io.github.piresrenan.orderhub.orders.application.port.out.OrderRepository;
import io.github.piresrenan.orderhub.orders.application.port.out.TransactionExecutor;
import io.github.piresrenan.orderhub.orders.domain.model.Order;
import io.github.piresrenan.orderhub.orders.domain.model.OrderItem;

/**
 * Coordinates creation of a new Order and the durable Inventory commitment
 * required for that Order to be accepted.
 */
public final class CreateOrderService
        implements CreateOrderUseCase {

    private final OrderRepository orderRepository;
    private final OrderIdGenerator orderIdGenerator;
    private final TransactionExecutor transactionExecutor;
    private final CommitOrderInventoryUseCase inventory;

    public CreateOrderService(
            OrderRepository orderRepository,
            OrderIdGenerator orderIdGenerator,
            TransactionExecutor transactionExecutor,
            CommitOrderInventoryUseCase inventory) {

        this.orderRepository =
                orderRepository;

        this.orderIdGenerator =
                orderIdGenerator;

        this.transactionExecutor =
                transactionExecutor;

        this.inventory =
                inventory;
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

        /*
         * Domain construction remains outside the transaction so invalid Orders
         * consume no database transaction.
         */
        var order =
                Order.create(
                        orderIdGenerator.generate(),
                        command.tenantId(),
                        command.customerId(),
                        items);

        var inventoryCommand =
                new CommitOrderInventoryCommand(
                        order.tenantId(),
                        order.id(),
                        order.items()
                                .stream()
                                .map(item ->
                                        new CommitOrderInventoryCommand.Demand(
                                                item.variantId(),
                                                item.quantity()))
                                .toList());

        return transactionExecutor.execute(
                () -> {

                    var persistedOrder =
                            orderRepository.save(
                                    order);

                    inventory.commit(
                            inventoryCommand);

                    return persistedOrder;
                });
    }
}
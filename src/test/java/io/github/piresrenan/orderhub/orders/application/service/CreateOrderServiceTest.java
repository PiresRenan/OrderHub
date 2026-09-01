package io.github.piresrenan.orderhub.orders.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderCommand;
import io.github.piresrenan.orderhub.orders.application.port.out.OrderIdGenerator;
import io.github.piresrenan.orderhub.orders.application.port.out.OrderRepository;
import io.github.piresrenan.orderhub.orders.application.port.out.TransactionExecutor;
import io.github.piresrenan.orderhub.orders.domain.model.Order;
import io.github.piresrenan.orderhub.orders.domain.model.OrderItem;
import io.github.piresrenan.orderhub.orders.domain.model.OrderStatus;

class CreateOrderServiceTest {

    @Test
    void createsAndPersistsOrderInsideTransactionBoundary() {

        var orderId =
                UUID.randomUUID();

        var tenantId =
                UUID.randomUUID();

        var customerId =
                UUID.randomUUID();

        var productId =
                UUID.randomUUID();

        var transactionExecutor =
                new RecordingTransactionExecutor();

        var repository =
                new RecordingOrderRepository(
                        transactionExecutor::isActive);

        OrderIdGenerator idGenerator =
                () -> orderId;

        var service =
                new CreateOrderService(
                        repository,
                        idGenerator,
                        transactionExecutor);

        var command =
                new CreateOrderCommand(
                        tenantId,
                        customerId,
                        List.of(
                                new CreateOrderCommand.Item(
                                        productId,
                                        2)));

        var order =
                service.create(
                        command);

        assertThat(order.id())
                .isEqualTo(orderId);

        assertThat(order.tenantId())
                .isEqualTo(tenantId);

        assertThat(order.customerId())
                .isEqualTo(customerId);

        assertThat(order.items())
                .hasSize(1);

        assertThat(
                order.items()
                        .getFirst()
                        .productId())
                .isEqualTo(productId);

        assertThat(
                order.items()
                        .getFirst()
                        .quantity())
                .isEqualTo(2);

        assertThat(order.status())
                .isEqualTo(OrderStatus.CREATED);

        assertThat(repository.savedOrder)
                .isSameAs(order);

        assertThat(repository.saveCount)
                .isEqualTo(1);

        assertThat(
                repository.saveObservedInsideTransaction)
                .isTrue();

        assertThat(
                transactionExecutor.executionCount)
                .isEqualTo(1);
    }

    @Test
    void mapsMultipleItems() {

        var transactionExecutor =
                new RecordingTransactionExecutor();

        var repository =
                new RecordingOrderRepository(
                        transactionExecutor::isActive);

        var generatedId =
                UUID.randomUUID();

        var service =
                new CreateOrderService(
                        repository,
                        () -> generatedId,
                        transactionExecutor);

        var firstProduct =
                UUID.randomUUID();

        var secondProduct =
                UUID.randomUUID();

        var order =
                service.create(
                        new CreateOrderCommand(
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                List.of(
                                        new CreateOrderCommand.Item(
                                                firstProduct,
                                                2),
                                        new CreateOrderCommand.Item(
                                                secondProduct,
                                                4))));

        assertThat(order.items())
                .extracting(
                        OrderItem::productId)
                .containsExactly(
                        firstProduct,
                        secondProduct);

        assertThat(order.items())
                .extracting(
                        OrderItem::quantity)
                .containsExactly(
                        2,
                        4);
    }

    @Test
    void generatesOrderIdExactlyOnce() {

        var calls =
                new AtomicInteger();

        var generatedId =
                UUID.randomUUID();

        OrderIdGenerator generator =
                () -> {
                    calls.incrementAndGet();

                    return generatedId;
                };

        var transactionExecutor =
                new RecordingTransactionExecutor();

        var service =
                new CreateOrderService(
                        new RecordingOrderRepository(
                                transactionExecutor::isActive),
                        generator,
                        transactionExecutor);

        var order =
                service.create(
                        new CreateOrderCommand(
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                List.of(
                                        new CreateOrderCommand.Item(
                                                UUID.randomUUID(),
                                                1))));

        assertThat(calls)
                .hasValue(1);

        assertThat(order.id())
                .isEqualTo(generatedId);

        assertThat(
                transactionExecutor.executionCount)
                .isEqualTo(1);
    }

    @Test
    void doesNotPersistOrOpenTransactionForInvalidOrder() {

        var transactionExecutor =
                new RecordingTransactionExecutor();

        var repository =
                new RecordingOrderRepository(
                        transactionExecutor::isActive);

        var service =
                new CreateOrderService(
                        repository,
                        UUID::randomUUID,
                        transactionExecutor);

        var invalidCommand =
                new CreateOrderCommand(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        List.of());

        assertThatThrownBy(() ->
                service.create(
                        invalidCommand))
                .isInstanceOf(
                        IllegalArgumentException.class)
                .hasMessage(
                        "Order must contain at least one item");

        assertThat(repository.saveCount)
                .isZero();

        assertThat(repository.savedOrder)
                .isNull();

        assertThat(
                transactionExecutor.executionCount)
                .isZero();
    }

    private static final class RecordingTransactionExecutor
            implements TransactionExecutor {

        private int executionCount;
        private boolean active;

        @Override
        public <T> T execute(
                Supplier<T> work) {

            executionCount++;
            active = true;

            try {
                return work.get();
            } finally {
                active = false;
            }
        }

        boolean isActive() {
            return active;
        }
    }

    private static final class RecordingOrderRepository
            implements OrderRepository {

        private final BooleanSupplier transactionActive;

        private Order savedOrder;
        private int saveCount;
        private boolean saveObservedInsideTransaction;

        RecordingOrderRepository(
                BooleanSupplier transactionActive) {

            this.transactionActive =
                    transactionActive;
        }

        @Override
        public Order save(
                Order order) {

            this.savedOrder =
                    order;

            this.saveCount++;

            this.saveObservedInsideTransaction =
                    transactionActive.getAsBoolean();

            return order;
        }

        @Override
        public Optional<Order> findById(
                UUID tenantId,
                UUID orderId) {

            return Optional.empty();
        }
    }
}
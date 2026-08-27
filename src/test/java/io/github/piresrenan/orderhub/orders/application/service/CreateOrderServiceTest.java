package io.github.piresrenan.orderhub.orders.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;

import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderCommand;
import io.github.piresrenan.orderhub.orders.application.port.out.OrderIdGenerator;
import io.github.piresrenan.orderhub.orders.application.port.out.OrderRepository;
import io.github.piresrenan.orderhub.orders.domain.model.Order;
import io.github.piresrenan.orderhub.orders.domain.model.OrderItem;
import io.github.piresrenan.orderhub.orders.domain.model.OrderStatus;

class CreateOrderServiceTest {

    @Test
    void createsAndPersistsOrder() {
        // Why: order creation must coordinate identity generation, domain creation
        // and persistence through the output port as one application use case.
        // Covers: complete happy-path orchestration of CreateOrderService.
        // Prevents: orders being created without persistence, persisted with incorrect
        // data, or returned with a state different from the domain aggregate.

        var orderId = UUID.randomUUID();
        var tenantId = UUID.randomUUID();
        var customerId = UUID.randomUUID();
        var productId = UUID.randomUUID();

        var repository = new RecordingOrderRepository();

        OrderIdGenerator idGenerator = () -> orderId;

        var service = new CreateOrderService(
                repository,
                idGenerator);

        var command = new CreateOrderCommand(
                tenantId,
                customerId,
                List.of(
                        new CreateOrderCommand.Item(
                                productId,
                                2)));

        var order = service.create(command);

        assertThat(order.id()).isEqualTo(orderId);
        assertThat(order.tenantId()).isEqualTo(tenantId);
        assertThat(order.customerId()).isEqualTo(customerId);

        assertThat(order.items()).hasSize(1);
        assertThat(order.items().getFirst().productId())
                .isEqualTo(productId);
        assertThat(order.items().getFirst().quantity())
                .isEqualTo(2);

        assertThat(order.status())
                .isEqualTo(OrderStatus.CREATED);

        assertThat(repository.savedOrder)
                .isSameAs(order);

        assertThat(repository.saveCount)
                .isEqualTo(1);
    }

    @Test
    void mapsMultipleItems() {
        // Why: real orders commonly contain multiple products and quantities.
        // Covers: mapping every application command item into a domain OrderItem.
        // Prevents: accidental truncation, overwriting, reordering or loss of items
        // when translating the command into the aggregate.

        var repository = new RecordingOrderRepository();
        var generatedId = UUID.randomUUID();

        var service = new CreateOrderService(
                repository,
                () -> generatedId);

        var firstProduct = UUID.randomUUID();
        var secondProduct = UUID.randomUUID();

        var order = service.create(
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
                .extracting(OrderItem::productId)
                .containsExactly(
                        firstProduct,
                        secondProduct);

        assertThat(order.items())
                .extracting(OrderItem::quantity)
                .containsExactly(
                        2,
                        4);
    }

    @Test
    void generatesOrderIdExactlyOnce() {
        // Why: identity generation may later depend on database, distributed or
        // externally coordinated mechanisms and must have deterministic cardinality.
        // Covers: interaction count with OrderIdGenerator during one use-case execution.
        // Prevents: multiple IDs being generated for the same logical order and
        // inconsistencies between persisted and returned aggregate identities.

        var calls = new AtomicInteger();
        var generatedId = UUID.randomUUID();

        OrderIdGenerator generator = () -> {
            calls.incrementAndGet();
            return generatedId;
        };

        var service = new CreateOrderService(
                new RecordingOrderRepository(),
                generator);

        var order = service.create(
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
    }

    @Test
    void doesNotPersistInvalidOrder() {
        // Why: persistence must only receive aggregates that successfully satisfy
        // domain invariants.
        // Covers: execution ordering between domain creation and repository invocation.
        // Prevents: invalid or partially constructed orders from crossing the
        // persistence boundary when domain validation fails.

        var repository = new RecordingOrderRepository();

        var service = new CreateOrderService(
                repository,
                UUID::randomUUID);

        var invalidCommand = new CreateOrderCommand(
                UUID.randomUUID(),
                UUID.randomUUID(),
                List.of());

        assertThatThrownBy(() -> service.create(invalidCommand))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Order must contain at least one item");

        assertThat(repository.saveCount)
                .isZero();

        assertThat(repository.savedOrder)
                .isNull();
    }

    private static final class RecordingOrderRepository
            implements OrderRepository {

        private Order savedOrder;
        private int saveCount;

        /**
         * Records persistence interactions performed by the application service.
         *
         * <p>This test double intentionally contains no infrastructure behavior.
         * Its only responsibility is to expose whether an aggregate crossed the
         * repository output port and how many times that occurred.</p>
         *
         * @param order aggregate sent for persistence
         * @return the same aggregate, reproducing the contract currently expected
         *         from OrderRepository.save
         */
        @Override
        public Order save(Order order) {
            this.savedOrder = order;
            this.saveCount++;

            return order;
        }
    }
}
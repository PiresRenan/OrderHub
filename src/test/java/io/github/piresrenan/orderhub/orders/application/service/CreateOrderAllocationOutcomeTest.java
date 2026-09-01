package io.github.piresrenan.orderhub.orders.application.service;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import io.github.piresrenan.orderhub.inventory.application.port.in.CommitOrderInventoryCommand;
import io.github.piresrenan.orderhub.inventory.application.port.in.CommitOrderInventoryUseCase;
import io.github.piresrenan.orderhub.inventory.application.port.in.InventoryAllocationOutcome;
import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderAllocationOutcome;
import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderCommand;
import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderResult;
import io.github.piresrenan.orderhub.orders.application.port.out.OrderRepository;
import io.github.piresrenan.orderhub.orders.application.port.out.TransactionExecutor;
import io.github.piresrenan.orderhub.orders.domain.model.Order;
import io.github.piresrenan.orderhub.orders.domain.model.OrderStatus;

class CreateOrderAllocationOutcomeTest {

    @Test
    void mapsFullyAllocatedWithoutChangingOrderLifecycle() {
        assertMapping(
                InventoryAllocationOutcome.FULLY_ALLOCATED,
                CreateOrderAllocationOutcome.FULLY_ALLOCATED);
    }

    @Test
    void mapsPartialBackorderWithoutChangingOrderLifecycle() {
        assertMapping(
                InventoryAllocationOutcome.PARTIALLY_BACKORDERED,
                CreateOrderAllocationOutcome.PARTIALLY_BACKORDERED);
    }

    @Test
    void mapsFullBackorderWithoutChangingOrderLifecycle() {
        assertMapping(
                InventoryAllocationOutcome.FULLY_BACKORDERED,
                CreateOrderAllocationOutcome.FULLY_BACKORDERED);
    }

    private static void assertMapping(
            InventoryAllocationOutcome inventoryOutcome,
            CreateOrderAllocationOutcome expectedOutcome) {

        var orderId =
                UUID.randomUUID();

        var tenantId =
                UUID.randomUUID();

        var customerId =
                UUID.randomUUID();

        var variantId =
                UUID.randomUUID();

        var service =
                new CreateOrderService(
                        new EchoOrderRepository(),
                        () -> orderId,
                        new DirectTransactionExecutor(),
                        new FixedInventoryCommitter(
                                inventoryOutcome));

        CreateOrderResult result =
                service.create(
                        new CreateOrderCommand(
                                tenantId,
                                customerId,
                                List.of(
                                        new CreateOrderCommand.Item(
                                                variantId,
                                                2))));

        assertThat(result.order().id())
                .isEqualTo(orderId);

        assertThat(result.order().status())
                .isEqualTo(
                        OrderStatus.CREATED);

        assertThat(result.allocationOutcome())
                .isEqualTo(
                        expectedOutcome);
    }

    private static final class FixedInventoryCommitter
            implements CommitOrderInventoryUseCase {

        private final InventoryAllocationOutcome outcome;

        FixedInventoryCommitter(
                InventoryAllocationOutcome outcome) {

            this.outcome =
                    outcome;
        }

        @Override
        public InventoryAllocationOutcome commit(
                CommitOrderInventoryCommand command) {

            return outcome;
        }
    }

    private static final class EchoOrderRepository
            implements OrderRepository {

        @Override
        public Order save(
                Order order) {

            return order;
        }

        @Override
        public Optional<Order> findById(
                UUID tenantId,
                UUID orderId) {

            return Optional.empty();
        }
    }

    private static final class DirectTransactionExecutor
            implements TransactionExecutor {

        @Override
        public <T> T execute(
                Supplier<T> work) {

            return work.get();
        }
    }
}
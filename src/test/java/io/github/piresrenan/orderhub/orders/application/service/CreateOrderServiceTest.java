package io.github.piresrenan.orderhub.orders.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import io.github.piresrenan.orderhub.catalog.application.port.in.orderability.CatalogOrderabilityRejectedException;
import io.github.piresrenan.orderhub.catalog.application.port.in.orderability.ValidateOrderableVariantsCommand;
import io.github.piresrenan.orderhub.catalog.application.port.in.orderability.ValidateOrderableVariantsUseCase;
import io.github.piresrenan.orderhub.inventory.application.port.in.CommitOrderInventoryCommand;
import io.github.piresrenan.orderhub.inventory.application.port.in.CommitOrderInventoryUseCase;
import io.github.piresrenan.orderhub.inventory.application.port.in.InventoryAllocationOutcome;
import io.github.piresrenan.orderhub.inventory.application.port.in.InventoryCommitmentRejectedException;
import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderCommand;
import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderIdempotencyKeyDigest;
import io.github.piresrenan.orderhub.orders.application.port.out.OrderIdGenerator;
import io.github.piresrenan.orderhub.orders.application.port.out.OrderRepository;
import io.github.piresrenan.orderhub.orders.application.port.out.TransactionExecutor;
import io.github.piresrenan.orderhub.orders.domain.model.Order;

class CreateOrderServiceTest {

    @Test
    void persistsOrderThenValidatesCatalogThenCommitsInventoryInsideSameTransaction() {

        var events =
                new ArrayList<String>();

        var transaction =
                new RecordingTransactionExecutor();

        var repository =
                new RecordingOrderRepository(
                        transaction::isActive,
                        events);

        var catalog =
                new RecordingCatalogValidator(
                        transaction::isActive,
                        events);

        var inventory =
                new RecordingInventoryCommitter(
                        transaction::isActive,
                        events);

        var orderId =
                UUID.randomUUID();

        var tenantId =
                UUID.randomUUID();

        var customerId =
                UUID.randomUUID();

        var variantId =
                UUID.randomUUID();

        var service =
                CreateOrderServiceTestFactory.create(
                        repository,
                        () -> orderId,
                        transaction,
                        catalog,
                        inventory);

        var result =
                service.create(
                        new CreateOrderCommand(
                                tenantId,
                                customerId,
                                List.of(
                                        new CreateOrderCommand.Item(
                                                variantId,
                                                2)),
                                CreateOrderIdempotencyKeyDigest.of(new byte[32])));

        assertThat(result.order().id())
                .isEqualTo(
                        orderId);

        assertThat(repository.saveCount)
                .isEqualTo(1);

        assertThat(repository.saveObservedInsideTransaction)
                .isTrue();

        assertThat(catalog.validateObservedInsideTransaction)
                .isTrue();

        assertThat(inventory.commitObservedInsideTransaction)
                .isTrue();

        assertThat(events)
                .containsExactly(
                        "order",
                        "catalog",
                        "inventory");

        assertThat(transaction.executionCount)
                .isEqualTo(1);

        assertThat(catalog.command.tenantId())
                .isEqualTo(
                        tenantId);

        assertThat(catalog.command.variantIds())
                .containsExactly(
                        variantId);

        assertThat(inventory.command.tenantId())
                .isEqualTo(
                        tenantId);

        assertThat(inventory.command.orderId())
                .isEqualTo(
                        orderId);
    }

    @Test
    void forwardsDuplicateVariantIdentityAndLeavesCatalogDeduplicationToCatalog() {

        var transaction =
                new RecordingTransactionExecutor();

        var catalog =
                new RecordingCatalogValidator(
                        transaction::isActive,
                        new ArrayList<>());

        var inventory =
                new RecordingInventoryCommitter(
                        transaction::isActive,
                        new ArrayList<>());

        var variantId =
                UUID.randomUUID();

        var service =
                CreateOrderServiceTestFactory.create(
                        new RecordingOrderRepository(
                                transaction::isActive,
                                new ArrayList<>()),
                        UUID::randomUUID,
                        transaction,
                        catalog,
                        inventory);

        service.create(
                new CreateOrderCommand(
                        UUID.randomUUID(),
                        UUID.randomUUID(),
                        List.of(
                                new CreateOrderCommand.Item(
                                        variantId,
                                        2),
                                new CreateOrderCommand.Item(
                                        variantId,
                                        3)),
                        CreateOrderIdempotencyKeyDigest.of(new byte[32])));

        assertThat(catalog.command.variantIds())
                .containsExactly(
                        variantId,
                        variantId);

        assertThat(inventory.command.demands())
                .containsExactly(
                        new CommitOrderInventoryCommand.Demand(
                                variantId,
                                2),
                        new CommitOrderInventoryCommand.Demand(
                                variantId,
                                3));
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

        var transaction =
                new RecordingTransactionExecutor();

        var service =
                CreateOrderServiceTestFactory.create(
                        new RecordingOrderRepository(
                                transaction::isActive,
                                new ArrayList<>()),
                        generator,
                        transaction,
                        new RecordingCatalogValidator(
                                transaction::isActive,
                                new ArrayList<>()),
                        new RecordingInventoryCommitter(
                                transaction::isActive,
                                new ArrayList<>()));

        var result =
                service.create(
                        new CreateOrderCommand(
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                List.of(
                                        new CreateOrderCommand.Item(
                                                UUID.randomUUID(),
                                                1)),
                                CreateOrderIdempotencyKeyDigest.of(new byte[32])));

        assertThat(calls)
                .hasValue(1);

        assertThat(result.order().id())
                .isEqualTo(
                        generatedId);
    }

    @Test
    void doesNotOpenTransactionOrTouchCollaboratorsForInvalidOrder() {

        var transaction =
                new RecordingTransactionExecutor();

        var repository =
                new RecordingOrderRepository(
                        transaction::isActive,
                        new ArrayList<>());

        var catalog =
                new RecordingCatalogValidator(
                        transaction::isActive,
                        new ArrayList<>());

        var inventory =
                new RecordingInventoryCommitter(
                        transaction::isActive,
                        new ArrayList<>());

        var service =
                CreateOrderServiceTestFactory.create(
                        repository,
                        UUID::randomUUID,
                        transaction,
                        catalog,
                        inventory);

        assertThatThrownBy(() ->
                service.create(
                        new CreateOrderCommand(
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                List.of(),
                                CreateOrderIdempotencyKeyDigest.of(new byte[32]))))
                .isInstanceOf(
                        IllegalArgumentException.class)
                .hasMessage(
                        "Order must contain at least one item");

        assertThat(transaction.executionCount)
                .isZero();

        assertThat(repository.saveCount)
                .isZero();

        assertThat(catalog.validateCount)
                .isZero();

        assertThat(inventory.commitCount)
                .isZero();
    }

    @Test
    void propagatesCatalogRejectionAfterOrderPersistenceAndBeforeInventory() {

        var transaction =
                new RecordingTransactionExecutor();

        var events =
                new ArrayList<String>();

        var repository =
                new RecordingOrderRepository(
                        transaction::isActive,
                        events);

        var catalog =
                new RecordingCatalogValidator(
                        transaction::isActive,
                        events);

        var inventory =
                new RecordingInventoryCommitter(
                        transaction::isActive,
                        events);

        var rejection =
                new CatalogOrderabilityRejectedException();

        catalog.failure =
                rejection;

        var service =
                CreateOrderServiceTestFactory.create(
                        repository,
                        UUID::randomUUID,
                        transaction,
                        catalog,
                        inventory);

        assertThatThrownBy(() ->
                service.create(
                        new CreateOrderCommand(
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                List.of(
                                        new CreateOrderCommand.Item(
                                                UUID.randomUUID(),
                                                1)),
                                CreateOrderIdempotencyKeyDigest.of(new byte[32]))))
                .isSameAs(
                        rejection);

        assertThat(events)
                .containsExactly(
                        "order",
                        "catalog");

        assertThat(repository.saveCount)
                .isEqualTo(1);

        assertThat(catalog.validateCount)
                .isEqualTo(1);

        assertThat(inventory.commitCount)
                .isZero();
    }

    @Test
    void propagatesInventoryRejectionAfterCatalogAcceptance() {

        var transaction =
                new RecordingTransactionExecutor();

        var events =
                new ArrayList<String>();

        var repository =
                new RecordingOrderRepository(
                        transaction::isActive,
                        events);

        var catalog =
                new RecordingCatalogValidator(
                        transaction::isActive,
                        events);

        var inventory =
                new RecordingInventoryCommitter(
                        transaction::isActive,
                        events);

        var rejection =
                new InventoryCommitmentRejectedException();

        inventory.failure =
                rejection;

        var service =
                CreateOrderServiceTestFactory.create(
                        repository,
                        UUID::randomUUID,
                        transaction,
                        catalog,
                        inventory);

        assertThatThrownBy(() ->
                service.create(
                        new CreateOrderCommand(
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                List.of(
                                        new CreateOrderCommand.Item(
                                                UUID.randomUUID(),
                                                1)),
                                CreateOrderIdempotencyKeyDigest.of(new byte[32]))))
                .isSameAs(
                        rejection);

        assertThat(events)
                .containsExactly(
                        "order",
                        "catalog",
                        "inventory");

        assertThat(repository.saveCount)
                .isEqualTo(1);

        assertThat(catalog.validateCount)
                .isEqualTo(1);

        assertThat(inventory.commitCount)
                .isEqualTo(1);
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
        private final List<String> events;

        private int saveCount;
        private boolean saveObservedInsideTransaction;

        RecordingOrderRepository(
                BooleanSupplier transactionActive,
                List<String> events) {

            this.transactionActive =
                    transactionActive;

            this.events =
                    events;
        }

        @Override
        public Order save(
                Order order) {

            saveCount++;

            saveObservedInsideTransaction =
                    transactionActive.getAsBoolean();

            events.add(
                    "order");

            return order;
        }

        @Override
        public Optional<Order> findById(
                UUID tenantId,
                UUID orderId) {

            return Optional.empty();
        }
    }

    private static final class RecordingCatalogValidator
            implements ValidateOrderableVariantsUseCase {

        private final BooleanSupplier transactionActive;
        private final List<String> events;

        private ValidateOrderableVariantsCommand command;
        private int validateCount;
        private boolean validateObservedInsideTransaction;
        private RuntimeException failure;

        RecordingCatalogValidator(
                BooleanSupplier transactionActive,
                List<String> events) {

            this.transactionActive =
                    transactionActive;

            this.events =
                    events;
        }

        @Override
        public void validate(
                ValidateOrderableVariantsCommand command) {

            this.command =
                    command;

            validateCount++;

            validateObservedInsideTransaction =
                    transactionActive.getAsBoolean();

            events.add(
                    "catalog");

            if (failure != null) {

                throw failure;
            }
        }
    }

    private static final class RecordingInventoryCommitter
            implements CommitOrderInventoryUseCase {

        private final BooleanSupplier transactionActive;
        private final List<String> events;

        private CommitOrderInventoryCommand command;
        private int commitCount;
        private boolean commitObservedInsideTransaction;
        private RuntimeException failure;

        RecordingInventoryCommitter(
                BooleanSupplier transactionActive,
                List<String> events) {

            this.transactionActive =
                    transactionActive;

            this.events =
                    events;
        }

        @Override
        public InventoryAllocationOutcome commit(
                CommitOrderInventoryCommand command) {

            this.command =
                    command;

            commitCount++;

            commitObservedInsideTransaction =
                    transactionActive.getAsBoolean();

            events.add(
                    "inventory");

            if (failure != null) {

                throw failure;
            }

            return InventoryAllocationOutcome.FULLY_ALLOCATED;
        }
    }
}

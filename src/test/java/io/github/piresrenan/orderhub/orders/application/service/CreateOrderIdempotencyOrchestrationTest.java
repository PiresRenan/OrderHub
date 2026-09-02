package io.github.piresrenan.orderhub.orders.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.InvocationTargetException;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.function.BooleanSupplier;
import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import io.github.piresrenan.orderhub.catalog.application.port.in.orderability.ValidateOrderableVariantsUseCase;
import io.github.piresrenan.orderhub.inventory.application.port.in.CommitOrderInventoryUseCase;
import io.github.piresrenan.orderhub.inventory.application.port.in.InventoryAllocationOutcome;
import io.github.piresrenan.orderhub.orders.application.idempotency.CreateOrderRequestFingerprint;
import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderAllocationOutcome;
import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderCommand;
import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderIdempotencyKeyDigest;
import io.github.piresrenan.orderhub.orders.application.port.out.CreateOrderIdempotencyAcquisition;
import io.github.piresrenan.orderhub.orders.application.port.out.CreateOrderIdempotencyCompletion;
import io.github.piresrenan.orderhub.orders.application.port.out.CreateOrderIdempotencyRepository;
import io.github.piresrenan.orderhub.orders.application.port.out.OrderIdGenerator;
import io.github.piresrenan.orderhub.orders.application.port.out.OrderRepository;
import io.github.piresrenan.orderhub.orders.application.port.out.TransactionExecutor;
import io.github.piresrenan.orderhub.orders.domain.model.Order;
import io.github.piresrenan.orderhub.orders.domain.model.OrderStatus;

class CreateOrderIdempotencyOrchestrationTest {

    private static final String REUSED_EXCEPTION =
            "io.github.piresrenan.orderhub.orders.application.idempotency."
                    + "CreateOrderIdempotencyKeyReusedException";

    private static final UUID TENANT_ID =
            UUID.fromString(
                    "11111111-1111-1111-1111-111111111111");

    private static final UUID CUSTOMER_ID =
            UUID.fromString(
                    "22222222-2222-2222-2222-222222222222");

    private static final UUID VARIANT_ID =
            UUID.fromString(
                    "33333333-3333-3333-3333-333333333333");

    private static final UUID ORDER_ID =
            UUID.fromString(
                    "44444444-4444-4444-4444-444444444444");

    @Test
    void acquiresBeforeGeneratingIdentityAndCompletesAfterBusinessEffects() {

        var events =
                new ArrayList<String>();

        var transaction =
                new RecordingTransactionExecutor();

        var idempotency =
                new RecordingIdempotencyRepository(
                        transaction::isActive,
                        events,
                        new CreateOrderIdempotencyAcquisition.Acquired());

        var generatedIds =
                new AtomicInteger();

        OrderIdGenerator generator =
                () -> {

                    assertThat(transaction.isActive())
                            .isTrue();

                    events.add(
                            "id");

                    generatedIds.incrementAndGet();

                    return ORDER_ID;
                };

        var repository =
                new RecordingOrderRepository(
                        transaction::isActive,
                        events);

        ValidateOrderableVariantsUseCase catalog =
                command -> {

                    assertThat(transaction.isActive())
                            .isTrue();

                    events.add(
                            "catalog");
                };

        CommitOrderInventoryUseCase inventory =
                command -> {

                    assertThat(transaction.isActive())
                            .isTrue();

                    events.add(
                            "inventory");

                    return InventoryAllocationOutcome.FULLY_ALLOCATED;
                };

        var service =
                service(
                        repository,
                        generator,
                        transaction,
                        catalog,
                        inventory,
                        idempotency);

        var command =
                command(
                        2);

        var result =
                service.create(
                        command);

        assertThat(result.order().id())
                .isEqualTo(
                        ORDER_ID);

        assertThat(result.allocationOutcome())
                .isEqualTo(
                        CreateOrderAllocationOutcome.FULLY_ALLOCATED);

        assertThat(generatedIds)
                .hasValue(
                        1);

        assertThat(events)
                .containsExactly(
                        "acquire",
                        "id",
                        "order",
                        "catalog",
                        "inventory",
                        "complete");

        assertThat(idempotency.acquisitionObservedInsideTransaction)
                .isTrue();

        assertThat(idempotency.completionObservedInsideTransaction)
                .isTrue();

        assertThat(idempotency.observedFingerprint)
                .isEqualTo(
                        CreateOrderRequestFingerprint.from(
                                command));

        assertThat(idempotency.completion)
                .isEqualTo(
                        new CreateOrderIdempotencyCompletion(
                                ORDER_ID,
                                OrderStatus.CREATED,
                                CreateOrderAllocationOutcome.FULLY_ALLOCATED));

        assertThat(transaction.executionCount)
                .isEqualTo(
                        1);
    }

    @Test
    void replaysCompletedOutcomeWithoutRepeatingBusinessEffects() {

        var events =
                new ArrayList<String>();

        var transaction =
                new RecordingTransactionExecutor();

        var completion =
                new CreateOrderIdempotencyCompletion(
                        ORDER_ID,
                        OrderStatus.CREATED,
                        CreateOrderAllocationOutcome.PARTIALLY_BACKORDERED);

        var idempotency =
                new RecordingIdempotencyRepository(
                        transaction::isActive,
                        events,
                        new CreateOrderIdempotencyAcquisition.Replay(
                                completion));

        var generatedIds =
                new AtomicInteger();

        var repository =
                new RecordingOrderRepository(
                        transaction::isActive,
                        events);

        var catalogCalls =
                new AtomicInteger();

        var inventoryCalls =
                new AtomicInteger();

        var service =
                service(
                        repository,
                        () -> {

                            generatedIds.incrementAndGet();

                            return UUID.randomUUID();
                        },
                        transaction,
                        command ->
                                catalogCalls.incrementAndGet(),
                        command -> {

                            inventoryCalls.incrementAndGet();

                            return InventoryAllocationOutcome.FULLY_ALLOCATED;
                        },
                        idempotency);

        var command =
                command(
                        5);

        var result =
                service.create(
                        command);

        assertThat(result.order().id())
                .isEqualTo(
                        ORDER_ID);

        assertThat(result.order().tenantId())
                .isEqualTo(
                        TENANT_ID);

        assertThat(result.order().customerId())
                .isEqualTo(
                        CUSTOMER_ID);

        assertThat(result.order().status())
                .isEqualTo(
                        OrderStatus.CREATED);

        assertThat(result.order().items())
                .hasSize(
                        1);

        assertThat(result.order().items().getFirst().variantId())
                .isEqualTo(
                        VARIANT_ID);

        assertThat(result.order().items().getFirst().quantity())
                .isEqualTo(
                        5);

        assertThat(result.allocationOutcome())
                .isEqualTo(
                        CreateOrderAllocationOutcome.PARTIALLY_BACKORDERED);

        assertThat(generatedIds)
                .hasValue(
                        0);

        assertThat(repository.saveCount)
                .isZero();

        assertThat(catalogCalls)
                .hasValue(
                        0);

        assertThat(inventoryCalls)
                .hasValue(
                        0);

        assertThat(idempotency.completeCount)
                .isZero();

        assertThat(events)
                .containsExactly(
                        "acquire");

        assertThat(transaction.executionCount)
                .isEqualTo(
                        1);
    }

    @Test
    void rejectsSameDurableKeyForDifferentCanonicalRequestWithoutBusinessEffects() {

        var events =
                new ArrayList<String>();

        var transaction =
                new RecordingTransactionExecutor();

        var idempotency =
                new RecordingIdempotencyRepository(
                        transaction::isActive,
                        events,
                        new CreateOrderIdempotencyAcquisition.FingerprintConflict());

        var generatedIds =
                new AtomicInteger();

        var repository =
                new RecordingOrderRepository(
                        transaction::isActive,
                        events);

        var catalogCalls =
                new AtomicInteger();

        var inventoryCalls =
                new AtomicInteger();

        var service =
                service(
                        repository,
                        () -> {

                            generatedIds.incrementAndGet();

                            return UUID.randomUUID();
                        },
                        transaction,
                        command ->
                                catalogCalls.incrementAndGet(),
                        command -> {

                            inventoryCalls.incrementAndGet();

                            return InventoryAllocationOutcome.FULLY_ALLOCATED;
                        },
                        idempotency);

        assertThatThrownBy(() ->
                service.create(
                        command(
                                7)))
                .satisfies(exception ->
                        assertThat(
                                exception.getClass()
                                        .getName())
                                .isEqualTo(
                                        REUSED_EXCEPTION));

        assertThat(generatedIds)
                .hasValue(
                        0);

        assertThat(repository.saveCount)
                .isZero();

        assertThat(catalogCalls)
                .hasValue(
                        0);

        assertThat(inventoryCalls)
                .hasValue(
                        0);

        assertThat(idempotency.completeCount)
                .isZero();

        assertThat(events)
                .containsExactly(
                        "acquire");
    }

    private static CreateOrderService service(
            OrderRepository repository,
            OrderIdGenerator generator,
            TransactionExecutor transaction,
            ValidateOrderableVariantsUseCase catalog,
            CommitOrderInventoryUseCase inventory,
            CreateOrderIdempotencyRepository idempotency) {

        try {

            var constructor =
                    CreateOrderService.class.getConstructor(
                            OrderRepository.class,
                            OrderIdGenerator.class,
                            TransactionExecutor.class,
                            ValidateOrderableVariantsUseCase.class,
                            CommitOrderInventoryUseCase.class,
                            CreateOrderIdempotencyRepository.class);

            return constructor.newInstance(
                    repository,
                    generator,
                    transaction,
                    catalog,
                    inventory,
                    idempotency);

        } catch (NoSuchMethodException exception) {

            throw new AssertionError(
                    "CreateOrderService must require durable idempotency persistence",
                    exception);

        } catch (InvocationTargetException exception) {

            var cause =
                    exception.getCause();

            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }

            if (cause instanceof Error error) {
                throw error;
            }

            throw new AssertionError(
                    cause);

        } catch (ReflectiveOperationException exception) {

            throw new AssertionError(
                    exception);
        }
    }

    private static CreateOrderCommand command(
            int quantity) {

        var digest =
                new byte[32];

        digest[0] =
                1;

        return new CreateOrderCommand(
                TENANT_ID,
                CUSTOMER_ID,
                List.of(
                        new CreateOrderCommand.Item(
                                VARIANT_ID,
                                quantity)),
                CreateOrderIdempotencyKeyDigest.of(
                        digest));
    }

    private static final class RecordingTransactionExecutor
            implements TransactionExecutor {

        private boolean active;
        private int executionCount;

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

    private static final class RecordingIdempotencyRepository
            implements CreateOrderIdempotencyRepository {

        private final BooleanSupplier transactionActive;
        private final List<String> events;
        private final CreateOrderIdempotencyAcquisition acquisition;

        private boolean acquisitionObservedInsideTransaction;
        private boolean completionObservedInsideTransaction;

        private int completeCount;

        private CreateOrderRequestFingerprint observedFingerprint;
        private CreateOrderIdempotencyCompletion completion;

        private RecordingIdempotencyRepository(
                BooleanSupplier transactionActive,
                List<String> events,
                CreateOrderIdempotencyAcquisition acquisition) {

            this.transactionActive =
                    transactionActive;

            this.events =
                    events;

            this.acquisition =
                    acquisition;
        }

        @Override
        public CreateOrderIdempotencyAcquisition acquire(
                UUID tenantId,
                CreateOrderIdempotencyKeyDigest keyDigest,
                CreateOrderRequestFingerprint fingerprint) {

            acquisitionObservedInsideTransaction =
                    transactionActive.getAsBoolean();

            events.add(
                    "acquire");

            observedFingerprint =
                    fingerprint;

            return acquisition;
        }

        @Override
        public void complete(
                UUID tenantId,
                CreateOrderIdempotencyKeyDigest keyDigest,
                CreateOrderRequestFingerprint fingerprint,
                CreateOrderIdempotencyCompletion completion) {

            completionObservedInsideTransaction =
                    transactionActive.getAsBoolean();

            completeCount++;

            events.add(
                    "complete");

            this.completion =
                    completion;
        }
    }

    private static final class RecordingOrderRepository
            implements OrderRepository {

        private final BooleanSupplier transactionActive;
        private final List<String> events;

        private int saveCount;

        private RecordingOrderRepository(
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

            assertThat(transactionActive.getAsBoolean())
                    .isTrue();

            saveCount++;

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
}

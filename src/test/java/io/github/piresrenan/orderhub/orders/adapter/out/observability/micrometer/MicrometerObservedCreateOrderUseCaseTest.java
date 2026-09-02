package io.github.piresrenan.orderhub.orders.adapter.out.observability.micrometer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.SQLException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.transaction.TransactionTimedOutException;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.github.piresrenan.orderhub.catalog.application.port.in.orderability.CatalogOrderabilityRejectedException;
import io.github.piresrenan.orderhub.inventory.application.port.in.InventoryCommitmentRejectedException;
import io.github.piresrenan.orderhub.inventory.application.port.in.InventoryOperationException;
import io.github.piresrenan.orderhub.orders.application.idempotency.CreateOrderIdempotencyKeyReusedException;
import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderAllocationOutcome;
import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderCommand;
import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderIdempotencyKeyDigest;
import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderResult;
import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderUseCase;
import io.github.piresrenan.orderhub.orders.application.port.out.CreateOrderIdempotencyInProgressException;
import io.github.piresrenan.orderhub.orders.application.port.out.TransactionExecutionException;
import io.github.piresrenan.orderhub.orders.domain.model.Order;
import io.github.piresrenan.orderhub.orders.domain.model.OrderItem;

class MicrometerObservedCreateOrderUseCaseTest {

    private static final String ALLOCATION_METRIC =
            "orderhub.orders.create.allocation";

    private static final String FAILURE_METRIC =
            "orderhub.orders.create.failure";

    private static final UUID TENANT_ID =
            UUID.fromString(
                    "10000000-0000-0000-0000-000000000001");

    private static final UUID ORDER_ID =
            UUID.fromString(
                    "20000000-0000-0000-0000-000000000001");

    private static final UUID VARIANT_ID =
            UUID.fromString(
                    "30000000-0000-0000-0000-000000000001");

    private static final UUID CUSTOMER_ID =
            UUID.fromString(
                    "50000000-0000-0000-0000-000000000001");

    @ParameterizedTest
    @EnumSource(CreateOrderAllocationOutcome.class)
    void recordsEachSuccessfulAllocationOutcomeWithBoundedTagValues(
            CreateOrderAllocationOutcome outcome) {

        var registry =
                new SimpleMeterRegistry();

        CreateOrderUseCase delegate =
                command ->
                        result(outcome);

        var observed =
                new MicrometerObservedCreateOrderUseCase(
                        delegate,
                        registry);

        var result =
                observed.create(
                        command());

        assertThat(result.allocationOutcome())
                .isEqualTo(outcome);

        assertCounter(
                registry,
                ALLOCATION_METRIC,
                "outcome",
                metricValue(outcome));

        assertNoBusinessIdentityTags(
                registry);
    }

    @Test
    void doesNotClassifyIdempotencyKeyReuseAsCreateFailure() {

        var registry =
                new SimpleMeterRegistry();

        var failure =
                new CreateOrderIdempotencyKeyReusedException();

        CreateOrderUseCase delegate =
                command -> {
                    throw failure;
                };

        var observed =
                new MicrometerObservedCreateOrderUseCase(
                        delegate,
                        registry);

        assertThatThrownBy(() ->
                observed.create(
                        command()))
                .isSameAs(
                        failure);

        assertNoCreateFailureMeter(
                registry);
    }

    @Test
    void doesNotClassifyIdempotencyInProgressAsCreateFailure() {

        var registry =
                new SimpleMeterRegistry();

        var failure =
                new CreateOrderIdempotencyInProgressException(
                        new IllegalStateException(
                                "synthetic expected idempotency contention"));

        CreateOrderUseCase delegate =
                command -> {
                    throw failure;
                };

        var observed =
                new MicrometerObservedCreateOrderUseCase(
                        delegate,
                        registry);

        assertThatThrownBy(() ->
                observed.create(
                        command()))
                .isSameAs(
                        failure);

        assertNoCreateFailureMeter(
                registry);
    }

    @Test
    void recordsInsufficientInventoryWithoutChangingTheOriginalFailure() {

        var registry =
                new SimpleMeterRegistry();

        var failure =
                new InventoryCommitmentRejectedException();

        CreateOrderUseCase delegate =
                command -> {
                    throw failure;
                };

        var observed =
                new MicrometerObservedCreateOrderUseCase(
                        delegate,
                        registry);

        assertThatThrownBy(() ->
                observed.create(
                        command()))
                .isSameAs(failure);

        assertCounter(
                registry,
                FAILURE_METRIC,
                "reason",
                "insufficient_inventory");

        assertNoBusinessIdentityTags(
                registry);
    }

    @Test
    void recordsCatalogItemUnavailableWithoutChangingTheOriginalFailure() {

        var registry =
                new SimpleMeterRegistry();

        var failure =
                new CatalogOrderabilityRejectedException();

        CreateOrderUseCase delegate =
                command -> {
                    throw failure;
                };

        var observed =
                new MicrometerObservedCreateOrderUseCase(
                        delegate,
                        registry);

        assertThatThrownBy(() ->
                observed.create(
                        command()))
                .isSameAs(failure);

        assertCounter(
                registry,
                FAILURE_METRIC,
                "reason",
                "catalog_item_unavailable");

        assertNoBusinessIdentityTags(
                registry);
    }
    @Test
    void recordsPostgreSqlStatementTimeoutAsLockTimeout() {

        var registry =
                new SimpleMeterRegistry();

        var failure =
                new InventoryOperationException(
                        new SQLException(
                                "synthetic timeout",
                                "57014"));

        assertFailureReason(
                registry,
                failure,
                "lock_timeout");
    }

    @Test
    void recordsSpringTransactionTimeoutAsLockTimeout() {

        var registry =
                new SimpleMeterRegistry();

        var failure =
                new TransactionExecutionException(
                        new TransactionTimedOutException(
                                "synthetic transaction timeout"));

        assertFailureReason(
                registry,
                failure,
                "lock_timeout");
    }

    @Test
    void recordsPostgreSqlDeadlockWithBoundedReason() {

        var registry =
                new SimpleMeterRegistry();

        var failure =
                new InventoryOperationException(
                        new SQLException(
                                "synthetic deadlock",
                                "40P01"));

        assertFailureReason(
                registry,
                failure,
                "deadlock");
    }

    @Test
    void recordsTransientDatabaseFailureWithBoundedReason() {

        var registry =
                new SimpleMeterRegistry();

        var failure =
                new InventoryOperationException(
                        new SQLException(
                                "synthetic serialization failure",
                                "40001"));

        assertFailureReason(
                registry,
                failure,
                "transient_database");
    }

    @Test
    void recordsUnknownTechnicalFailureWithoutExceptionClassTag() {

        var registry =
                new SimpleMeterRegistry();

        var failure =
                new IllegalStateException(
                        "synthetic technical failure");

        assertFailureReason(
                registry,
                failure,
                "technical_failure");
    }

    private static void assertNoCreateFailureMeter(
            SimpleMeterRegistry registry) {

        assertThat(
                registry.getMeters()
                        .stream()
                        .filter(meter ->
                                FAILURE_METRIC.equals(
                                        meter.getId()
                                                .getName()))
                        .toList())
                .as(
                        "Expected idempotency control flow must not be classified as create failure")
                .isEmpty();
    }

    private static void assertFailureReason(
            SimpleMeterRegistry registry,
            RuntimeException failure,
            String expectedReason) {

        CreateOrderUseCase delegate =
                command -> {
                    throw failure;
                };

        var observed =
                new MicrometerObservedCreateOrderUseCase(
                        delegate,
                        registry);

        assertThatThrownBy(() ->
                observed.create(
                        command()))
                .isSameAs(failure);

        assertCounter(
                registry,
                FAILURE_METRIC,
                "reason",
                expectedReason);

        assertNoBusinessIdentityTags(
                registry);
    }

    private static void assertCounter(
            SimpleMeterRegistry registry,
            String metricName,
            String tagKey,
            String tagValue) {

        var counter =
                registry
                        .find(metricName)
                        .tag(
                                tagKey,
                                tagValue)
                        .counter();

        assertThat(counter)
                .as(
                        "%s{%s=%s}",
                        metricName,
                        tagKey,
                        tagValue)
                .isNotNull();

        assertThat(counter.count())
                .isEqualTo(1.0d);
    }

    private static void assertNoBusinessIdentityTags(
            SimpleMeterRegistry registry) {

        var forbiddenValues =
                Set.of(
                        TENANT_ID.toString(),
                        ORDER_ID.toString(),
                        VARIANT_ID.toString(),
                        CUSTOMER_ID.toString());

        for (var meter :
                registry.getMeters()) {

            for (var tag :
                    meter.getId().getTags()) {

                var key =
                        tag.getKey()
                                .toLowerCase(
                                        Locale.ROOT);

                assertThat(key)
                        .doesNotContain(
                                "tenant",
                                "order",
                                "variant",
                                "product",
                                "sku",
                                "customer");

                assertThat(tag.getValue())
                        .isNotIn(
                                forbiddenValues);
            }
        }
    }

    private static CreateOrderCommand command() {

        return new CreateOrderCommand(
                TENANT_ID,
                CUSTOMER_ID,
                List.of(
                        new CreateOrderCommand.Item(
                                VARIANT_ID,
                                1)),
                CreateOrderIdempotencyKeyDigest.of(new byte[32]));
    }

    private static CreateOrderResult result(
            CreateOrderAllocationOutcome outcome) {

        var order =
                Order.create(
                        ORDER_ID,
                        TENANT_ID,
                        CUSTOMER_ID,
                        List.of(
                                new OrderItem(
                                        VARIANT_ID,
                                        1)));

        return new CreateOrderResult(
                order,
                outcome);
    }

    private static String metricValue(
            CreateOrderAllocationOutcome outcome) {

        return switch (outcome) {
            case FULLY_ALLOCATED ->
                    "fully_allocated";
            case PARTIALLY_BACKORDERED ->
                    "partially_backordered";
            case FULLY_BACKORDERED ->
                    "fully_backordered";
        };
    }
}

package io.github.piresrenan.orderhub.orders.adapter.out.observability.micrometer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.lang.reflect.InvocationTargetException;
import java.util.List;
import java.util.Locale;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.github.piresrenan.orderhub.orders.application.idempotency.CreateOrderRequestFingerprint;
import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderAllocationOutcome;
import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderCommand;
import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderIdempotencyKeyDigest;
import io.github.piresrenan.orderhub.orders.application.port.out.CreateOrderIdempotencyAcquisition;
import io.github.piresrenan.orderhub.orders.application.port.out.CreateOrderIdempotencyCompletion;
import io.github.piresrenan.orderhub.orders.application.port.out.CreateOrderIdempotencyInProgressException;
import io.github.piresrenan.orderhub.orders.application.port.out.CreateOrderIdempotencyPersistenceException;
import io.github.piresrenan.orderhub.orders.application.port.out.CreateOrderIdempotencyRepository;
import io.github.piresrenan.orderhub.orders.domain.model.OrderStatus;

class MicrometerObservedCreateOrderIdempotencyRepositoryTest {

    private static final String OBSERVED_TYPE =
            "io.github.piresrenan.orderhub.orders.adapter.out.observability.micrometer."
                    + "MicrometerObservedCreateOrderIdempotencyRepository";

    private static final String METRIC =
            "orderhub.orders.idempotency";

    private static final String OUTCOME_TAG =
            "outcome";

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
    void recordsFirstExecutionWithBoundedOutcome() {

        var registry =
                new SimpleMeterRegistry();

        var delegate =
                new StubRepository();

        delegate.acquisition =
                new CreateOrderIdempotencyAcquisition.Acquired();

        var observed =
                observed(
                        delegate,
                        registry);

        var result =
                observed.acquire(
                        TENANT_ID,
                        keyDigest(),
                        fingerprint());

        assertThat(result)
                .isInstanceOf(
                        CreateOrderIdempotencyAcquisition.Acquired.class);

        assertCounter(
                registry,
                "first_execution",
                1.0d);

        assertNoIdentityTags(
                registry);
    }

    @Test
    void recordsReplayWithBoundedOutcome() {

        var registry =
                new SimpleMeterRegistry();

        var completion =
                completion();

        var delegate =
                new StubRepository();

        delegate.acquisition =
                new CreateOrderIdempotencyAcquisition.Replay(
                        completion);

        var observed =
                observed(
                        delegate,
                        registry);

        var result =
                observed.acquire(
                        TENANT_ID,
                        keyDigest(),
                        fingerprint());

        assertThat(result)
                .isEqualTo(
                        new CreateOrderIdempotencyAcquisition.Replay(
                                completion));

        assertCounter(
                registry,
                "replay",
                1.0d);

        assertNoIdentityTags(
                registry);
    }

    @Test
    void recordsFingerprintConflictWithBoundedOutcome() {

        var registry =
                new SimpleMeterRegistry();

        var delegate =
                new StubRepository();

        delegate.acquisition =
                new CreateOrderIdempotencyAcquisition.FingerprintConflict();

        var observed =
                observed(
                        delegate,
                        registry);

        var result =
                observed.acquire(
                        TENANT_ID,
                        keyDigest(),
                        fingerprint());

        assertThat(result)
                .isInstanceOf(
                        CreateOrderIdempotencyAcquisition.FingerprintConflict.class);

        assertCounter(
                registry,
                "fingerprint_conflict",
                1.0d);

        assertNoIdentityTags(
                registry);
    }

    @Test
    void recordsBoundedInProgressConflictWithoutChangingFailure() {

        var registry =
                new SimpleMeterRegistry();

        var failure =
                new CreateOrderIdempotencyInProgressException(
                        new IllegalStateException(
                                "synthetic contention"));

        var delegate =
                new StubRepository();

        delegate.acquireFailure =
                failure;

        var observed =
                observed(
                        delegate,
                        registry);

        assertThatThrownBy(() ->
                observed.acquire(
                        TENANT_ID,
                        keyDigest(),
                        fingerprint()))
                .isSameAs(
                        failure);

        assertCounter(
                registry,
                "in_progress_conflict",
                1.0d);

        assertNoIdentityTags(
                registry);
    }

    @Test
    void recordsAcquisitionTechnicalFailureWithoutChangingFailure() {

        var registry =
                new SimpleMeterRegistry();

        var failure =
                new CreateOrderIdempotencyPersistenceException(
                        "synthetic acquisition failure");

        var delegate =
                new StubRepository();

        delegate.acquireFailure =
                failure;

        var observed =
                observed(
                        delegate,
                        registry);

        assertThatThrownBy(() ->
                observed.acquire(
                        TENANT_ID,
                        keyDigest(),
                        fingerprint()))
                .isSameAs(
                        failure);

        assertCounter(
                registry,
                "technical_failure",
                1.0d);

        assertNoIdentityTags(
                registry);
    }

    @Test
    void recordsCompletionTechnicalFailureWithoutChangingFailure() {

        var registry =
                new SimpleMeterRegistry();

        var failure =
                new CreateOrderIdempotencyPersistenceException(
                        "synthetic completion failure");

        var delegate =
                new StubRepository();

        delegate.completeFailure =
                failure;

        var observed =
                observed(
                        delegate,
                        registry);

        assertThatThrownBy(() ->
                observed.complete(
                        TENANT_ID,
                        keyDigest(),
                        fingerprint(),
                        completion()))
                .isSameAs(
                        failure);

        assertCounter(
                registry,
                "technical_failure",
                1.0d);

        assertNoIdentityTags(
                registry);
    }

    private static CreateOrderIdempotencyRepository observed(
            CreateOrderIdempotencyRepository delegate,
            MeterRegistry registry) {

        var type =
                loadType(
                        OBSERVED_TYPE);

        assertThat(type)
                .as(
                        "Micrometer idempotency repository decorator must exist")
                .isNotNull();

        try {

            return (CreateOrderIdempotencyRepository) type
                    .getConstructor(
                            CreateOrderIdempotencyRepository.class,
                            MeterRegistry.class)
                    .newInstance(
                            delegate,
                            registry);

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

    private static Class<?> loadType(
            String name) {

        try {

            return Class.forName(
                    name);

        } catch (ClassNotFoundException ignored) {

            return null;
        }
    }

    private static void assertCounter(
            SimpleMeterRegistry registry,
            String outcome,
            double expectedCount) {

        var counter =
                registry
                        .find(
                                METRIC)
                        .tag(
                                OUTCOME_TAG,
                                outcome)
                        .counter();

        assertThat(counter)
                .as(
                        "%s{outcome=%s}",
                        METRIC,
                        outcome)
                .isNotNull();

        assertThat(counter.count())
                .isEqualTo(
                        expectedCount);
    }

    private static void assertNoIdentityTags(
            SimpleMeterRegistry registry) {

        var forbiddenValues =
                Set.of(
                        TENANT_ID.toString(),
                        CUSTOMER_ID.toString(),
                        VARIANT_ID.toString(),
                        ORDER_ID.toString());

        for (var meter :
                registry.getMeters()) {

            for (var tag :
                    meter.getId()
                            .getTags()) {

                var key =
                        tag.getKey()
                                .toLowerCase(
                                        Locale.ROOT);

                assertThat(key)
                        .doesNotContain(
                                "tenant",
                                "order",
                                "customer",
                                "variant",
                                "product",
                                "sku",
                                "key",
                                "digest",
                                "fingerprint");

                assertThat(tag.getValue())
                        .isNotIn(
                                forbiddenValues);
            }
        }
    }

    private static CreateOrderIdempotencyKeyDigest keyDigest() {

        var bytes =
                new byte[32];

        bytes[0] =
                9;

        return CreateOrderIdempotencyKeyDigest.of(
                bytes);
    }

    private static CreateOrderRequestFingerprint fingerprint() {

        return CreateOrderRequestFingerprint.from(
                new CreateOrderCommand(
                        TENANT_ID,
                        CUSTOMER_ID,
                        List.of(
                                new CreateOrderCommand.Item(
                                        VARIANT_ID,
                                        2)),
                        keyDigest()));
    }

    private static CreateOrderIdempotencyCompletion completion() {

        return new CreateOrderIdempotencyCompletion(
                ORDER_ID,
                OrderStatus.CREATED,
                CreateOrderAllocationOutcome.FULLY_ALLOCATED);
    }

    private static final class StubRepository
            implements CreateOrderIdempotencyRepository {

        private CreateOrderIdempotencyAcquisition acquisition =
                new CreateOrderIdempotencyAcquisition.Acquired();

        private RuntimeException acquireFailure;

        private RuntimeException completeFailure;

        @Override
        public CreateOrderIdempotencyAcquisition acquire(
                UUID tenantId,
                CreateOrderIdempotencyKeyDigest keyDigest,
                CreateOrderRequestFingerprint fingerprint) {

            if (acquireFailure != null) {
                throw acquireFailure;
            }

            return acquisition;
        }

        @Override
        public void complete(
                UUID tenantId,
                CreateOrderIdempotencyKeyDigest keyDigest,
                CreateOrderRequestFingerprint fingerprint,
                CreateOrderIdempotencyCompletion completion) {

            if (completeFailure != null) {
                throw completeFailure;
            }
        }
    }
}

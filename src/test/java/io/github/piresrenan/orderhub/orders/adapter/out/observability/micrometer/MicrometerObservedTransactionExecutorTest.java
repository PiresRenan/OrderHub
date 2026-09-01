package io.github.piresrenan.orderhub.orders.adapter.out.observability.micrometer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.function.Supplier;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;
import io.github.piresrenan.orderhub.orders.application.port.out.TransactionExecutor;

class MicrometerObservedTransactionExecutorTest {

    private static final String TRANSACTION_DURATION =
            "orderhub.orders.transaction.duration";

    @Test
    void recordsExactlyOneTransactionDurationOnSuccess() {

        var registry =
                new SimpleMeterRegistry();

        var observed =
                new MicrometerObservedTransactionExecutor(
                        new DirectTransactionExecutor(),
                        registry);

        var result =
                observed.execute(
                        () -> "committed");

        assertThat(result)
                .isEqualTo(
                        "committed");

        var timer =
                registry
                        .find(TRANSACTION_DURATION)
                        .timer();

        assertThat(timer)
                .isNotNull();

        assertThat(timer.count())
                .isEqualTo(1L);

        assertThat(timer.getId().getTags())
                .isEmpty();
    }

    @Test
    void recordsTransactionDurationOnFailureAndPreservesTheFailure() {

        var registry =
                new SimpleMeterRegistry();

        var observed =
                new MicrometerObservedTransactionExecutor(
                        new DirectTransactionExecutor(),
                        registry);

        var failure =
                new IllegalStateException(
                        "synthetic transaction failure");

        assertThatThrownBy(() ->
                observed.execute(() -> {
                    throw failure;
                }))
                .isSameAs(failure);

        var timer =
                registry
                        .find(TRANSACTION_DURATION)
                        .timer();

        assertThat(timer)
                .isNotNull();

        assertThat(timer.count())
                .isEqualTo(1L);

        assertThat(timer.getId().getTags())
                .isEmpty();
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
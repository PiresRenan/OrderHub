package io.github.piresrenan.orderhub.orders.adapter.out.observability.micrometer;

import java.util.Objects;
import java.util.function.Supplier;

import io.micrometer.core.instrument.MeterRegistry;
import io.micrometer.core.instrument.Timer;
import io.github.piresrenan.orderhub.orders.application.port.out.TransactionExecutor;

/**
 * Measures the exact application-owned Order transaction boundary.
 *
 * <p>
 * The timer intentionally has no business-identity tags. It measures both
 * successful and failed executions while preserving the delegate result or
 * exception unchanged.
 * </p>
 */
public final class MicrometerObservedTransactionExecutor
        implements TransactionExecutor {

    private static final String TRANSACTION_DURATION_METRIC =
            "orderhub.orders.transaction.duration";

    private final TransactionExecutor delegate;
    private final Timer transactionDuration;

    public MicrometerObservedTransactionExecutor(
            TransactionExecutor delegate,
            MeterRegistry meterRegistry) {

        this.delegate =
                Objects.requireNonNull(
                        delegate,
                        "delegate");

        Objects.requireNonNull(
                meterRegistry,
                "meterRegistry");

        this.transactionDuration =
                Timer.builder(
                                TRANSACTION_DURATION_METRIC)
                        .description(
                                "Duration of the create-Order database transaction")
                        .register(
                                meterRegistry);
    }

    @Override
    public <T> T execute(
            Supplier<T> work) {

        Objects.requireNonNull(
                work,
                "work");

        return transactionDuration.record(
                () ->
                        delegate.execute(
                                work));
    }
}
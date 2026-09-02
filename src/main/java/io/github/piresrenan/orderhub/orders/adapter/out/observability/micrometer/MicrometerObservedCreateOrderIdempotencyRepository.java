package io.github.piresrenan.orderhub.orders.adapter.out.observability.micrometer;

import java.util.Objects;
import java.util.UUID;

import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import io.github.piresrenan.orderhub.orders.application.idempotency.CreateOrderRequestFingerprint;
import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderIdempotencyKeyDigest;
import io.github.piresrenan.orderhub.orders.application.port.out.CreateOrderIdempotencyAcquisition;
import io.github.piresrenan.orderhub.orders.application.port.out.CreateOrderIdempotencyCompletion;
import io.github.piresrenan.orderhub.orders.application.port.out.CreateOrderIdempotencyInProgressException;
import io.github.piresrenan.orderhub.orders.application.port.out.CreateOrderIdempotencyPersistenceException;
import io.github.piresrenan.orderhub.orders.application.port.out.CreateOrderIdempotencyRepository;

/**
 * Low-cardinality Micrometer decorator for durable create-Order idempotency.
 *
 * <p>
 * The metric describes bounded idempotency-boundary events only. Tenant,
 * Order, Customer, Variant, key digest and request fingerprint values are
 * deliberately excluded from meter tags.
 * </p>
 */
public final class MicrometerObservedCreateOrderIdempotencyRepository
        implements CreateOrderIdempotencyRepository {

    private static final String METRIC =
            "orderhub.orders.idempotency";

    private static final String OUTCOME_TAG =
            "outcome";

    private final CreateOrderIdempotencyRepository delegate;

    private final MeterRegistry meterRegistry;

    public MicrometerObservedCreateOrderIdempotencyRepository(
            CreateOrderIdempotencyRepository delegate,
            MeterRegistry meterRegistry) {

        this.delegate =
                Objects.requireNonNull(
                        delegate,
                        "delegate");

        this.meterRegistry =
                Objects.requireNonNull(
                        meterRegistry,
                        "meterRegistry");
    }

    @Override
    public CreateOrderIdempotencyAcquisition acquire(
            UUID tenantId,
            CreateOrderIdempotencyKeyDigest keyDigest,
            CreateOrderRequestFingerprint fingerprint) {

        try {

            var acquisition =
                    delegate.acquire(
                            tenantId,
                            keyDigest,
                            fingerprint);

            switch (acquisition) {
                case CreateOrderIdempotencyAcquisition.Acquired ignored ->
                        record(
                                "first_execution");
                case CreateOrderIdempotencyAcquisition.Replay ignored ->
                        record(
                                "replay");
                case CreateOrderIdempotencyAcquisition.FingerprintConflict ignored ->
                        record(
                                "fingerprint_conflict");
            }

            return acquisition;

        } catch (CreateOrderIdempotencyInProgressException failure) {

            record(
                    "in_progress_conflict");

            throw failure;

        } catch (CreateOrderIdempotencyPersistenceException failure) {

            record(
                    "technical_failure");

            throw failure;
        }
    }

    @Override
    public void complete(
            UUID tenantId,
            CreateOrderIdempotencyKeyDigest keyDigest,
            CreateOrderRequestFingerprint fingerprint,
            CreateOrderIdempotencyCompletion completion) {

        try {

            delegate.complete(
                    tenantId,
                    keyDigest,
                    fingerprint,
                    completion);

        } catch (CreateOrderIdempotencyPersistenceException failure) {

            record(
                    "technical_failure");

            throw failure;
        }
    }

    private void record(
            String outcome) {

        Counter.builder(
                        METRIC)
                .description(
                        "Durable create-Order idempotency events by bounded outcome")
                .tag(
                        OUTCOME_TAG,
                        outcome)
                .register(
                        meterRegistry)
                .increment();
    }
}

package io.github.piresrenan.orderhub.authorization.adapter.out.observability;

import java.util.Objects;

import io.micrometer.core.instrument.MeterRegistry;

import io.github.piresrenan.orderhub.authorization.application.observability.AuthorizationDecisionObservation;
import io.github.piresrenan.orderhub.authorization.application.port.out.AuthorizationDecisionObserver;

/**
 * Micrometer adapter for bounded authorization decision metrics.
 */
public final class MicrometerAuthorizationDecisionObserver
        implements AuthorizationDecisionObserver {

    public static final String METRIC_NAME =
            "orderhub.authorization.decisions";

    private final MeterRegistry meterRegistry;

    public MicrometerAuthorizationDecisionObserver(
            MeterRegistry meterRegistry) {

        this.meterRegistry =
                Objects.requireNonNull(
                        meterRegistry,
                        "meterRegistry");
    }

    @Override
    public void observe(
            AuthorizationDecisionObservation observation) {

        Objects.requireNonNull(
                observation,
                "observation");

        meterRegistry.counter(
                METRIC_NAME,
                "decision",
                observation.decision()
                        .name(),
                "persona",
                observation.persona()
                        .name(),
                "permission",
                observation.permission()
                        .name(),
                "reason",
                observation.reason()
                        .name())
                .increment();
    }
}

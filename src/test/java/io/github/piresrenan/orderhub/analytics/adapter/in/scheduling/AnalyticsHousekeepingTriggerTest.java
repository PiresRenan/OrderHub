package io.github.piresrenan.orderhub.analytics.adapter.in.scheduling;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;

import org.junit.jupiter.api.Test;

import io.micrometer.core.instrument.simple.SimpleMeterRegistry;

import io.github.piresrenan.orderhub.analytics.application.service.WorkforceAuthorityChangeFactRetentionService;
import io.github.piresrenan.orderhub.analytics.config.AnalyticsHousekeepingProperties;

class AnalyticsHousekeepingTriggerTest {

    @Test
    void executesOneBatchAndRecordsOnlyBoundedDimensions() {
        // Why: a full batch must not turn a scheduled invocation into a drain loop.
        // Covers: exactly one full batch and fixed dataset/outcome metric tags.
        // Prevents: unbounded work per trigger and identifier cardinality leaks.
        var service = mock(WorkforceAuthorityChangeFactRetentionService.class);
        var now = Instant.parse("2026-09-08T12:00:00Z");
        when(service.purgeExpired(now, 25)).thenReturn(25);
        var registry = new SimpleMeterRegistry();
        var trigger = new AnalyticsHousekeepingTrigger(
                service,
                new AnalyticsHousekeepingProperties(
                        true, Duration.ofDays(90), 25, Duration.ofHours(1)),
                Clock.fixed(now, ZoneOffset.UTC),
                registry);

        trigger.runOneBatch();

        verify(service).purgeExpired(now, 25);
        assertThat(registry.counter(AnalyticsHousekeepingTrigger.METRIC,
                "dataset", "workforce_authority_change_facts",
                "outcome", "success").count()).isEqualTo(1);
        assertThat(registry.counter(AnalyticsHousekeepingTrigger.DELETED_METRIC,
                "dataset", "workforce_authority_change_facts").count())
                .isEqualTo(25);
        assertThat(registry.getMeters()).allSatisfy(meter ->
                assertThat(meter.getId().getTags()).allSatisfy(tag ->
                        assertThat(tag.getKey())
                                .isIn("dataset", "outcome")));
    }

    @Test
    void recordsFailureWithoutFalseSuccessAndRethrowsUnchanged() {
        // Why: a failed deletion must remain visible as a failure.
        // Covers: bounded failure metrics and unchanged exception propagation.
        // Prevents: false success or hidden persistence failure.
        var service = mock(WorkforceAuthorityChangeFactRetentionService.class);
        var now = Instant.parse("2026-09-08T12:00:00Z");
        var failure = new IllegalStateException("synthetic test failure");
        when(service.purgeExpired(now, 25)).thenThrow(failure);
        var registry = new SimpleMeterRegistry();
        var trigger = new AnalyticsHousekeepingTrigger(
                service,
                new AnalyticsHousekeepingProperties(
                        true, Duration.ofDays(90), 25, Duration.ofHours(1)),
                Clock.fixed(now, ZoneOffset.UTC), registry);

        assertThatThrownBy(trigger::runOneBatch).isSameAs(failure);
        assertThat(registry.counter(AnalyticsHousekeepingTrigger.METRIC,
                "dataset", "workforce_authority_change_facts",
                "outcome", "failure").count()).isEqualTo(1);
        assertThat(registry.counter(AnalyticsHousekeepingTrigger.METRIC,
                "dataset", "workforce_authority_change_facts",
                "outcome", "success").count()).isZero();
    }
}

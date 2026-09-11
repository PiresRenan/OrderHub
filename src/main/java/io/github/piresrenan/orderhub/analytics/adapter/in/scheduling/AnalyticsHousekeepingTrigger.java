package io.github.piresrenan.orderhub.analytics.adapter.in.scheduling;

import java.time.Clock;

import org.springframework.scheduling.annotation.Scheduled;

import io.micrometer.core.instrument.MeterRegistry;

import io.github.piresrenan.orderhub.analytics.application.service.WorkforceAuthorityChangeFactRetentionService;
import io.github.piresrenan.orderhub.analytics.config.AnalyticsHousekeepingProperties;

/** Triggers exactly one owner-bounded retention batch per schedule tick. */
public final class AnalyticsHousekeepingTrigger {

    static final String METRIC = "orderhub.analytics.housekeeping.executions";
    static final String DELETED_METRIC =
            "orderhub.analytics.housekeeping.rows.deleted";

    private final WorkforceAuthorityChangeFactRetentionService retention;
    private final AnalyticsHousekeepingProperties properties;
    private final Clock clock;
    private final MeterRegistry meters;

    public AnalyticsHousekeepingTrigger(
            WorkforceAuthorityChangeFactRetentionService retention,
            AnalyticsHousekeepingProperties properties,
            Clock clock,
            MeterRegistry meters) {
        this.retention = retention;
        this.properties = properties;
        this.clock = clock;
        this.meters = meters;
    }

    /** Runs one batch and records its durable outcome without hiding failures. */
    @Scheduled(fixedDelayString = "${orderhub.analytics.housekeeping.fixed-delay:1h}")
    public void runOneBatch() {
        try {
            var deleted = retention.purgeExpired(
                    clock.instant(), properties.batchSize());
            meters.counter(DELETED_METRIC,
                    "dataset", "workforce_authority_change_facts")
                    .increment(deleted);
            count("success");
        } catch (RuntimeException failure) {
            count("failure");
            throw failure;
        }
    }

    /** Records only the fixed dataset and the closed success/failure vocabulary. */
    private void count(String outcome) {
        meters.counter(METRIC,
                "dataset", "workforce_authority_change_facts",
                "outcome", outcome).increment();
    }
}

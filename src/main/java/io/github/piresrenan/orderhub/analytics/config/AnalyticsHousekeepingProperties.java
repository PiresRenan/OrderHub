package io.github.piresrenan.orderhub.analytics.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.validation.annotation.Validated;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotNull;

/** Safe, opt-in configuration for one bounded analytics cleanup batch. */
@Validated
@ConfigurationProperties(prefix = "orderhub.analytics.housekeeping")
public record AnalyticsHousekeepingProperties(
        boolean enabled,
        Duration retentionWindow,
        @Min(1) @Max(1000) int batchSize,
        @NotNull Duration fixedDelay) {

    /** Requires an operator policy when enabled, while permitting an unset opt-out. */
    @AssertTrue(message = "retention-window must be positive when housekeeping is enabled")
    public boolean isRetentionWindowValid() {
        return !enabled || (retentionWindow != null
                && !retentionWindow.isZero()
                && !retentionWindow.isNegative());
    }

    /** Rejects absent or nonpositive timing before scheduler registration. */
    @AssertTrue(message = "fixed-delay must be positive")
    public boolean isFixedDelayValid() {
        return fixedDelay != null
                && !fixedDelay.isZero()
                && !fixedDelay.isNegative();
    }
}

package io.github.piresrenan.orderhub.analytics.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import jakarta.validation.Validation;

class AnalyticsHousekeepingPropertiesTest {

    @Test
    void isDisabledSafelyByDefault() {
        var properties = new AnalyticsHousekeepingProperties(
                false, null, 100, Duration.ofHours(1));

        assertThat(properties.enabled()).isFalse();
        assertThat(properties.batchSize()).isEqualTo(100);
        assertThat(properties.fixedDelay()).isEqualTo(Duration.ofHours(1));
        assertThat(properties.isRetentionWindowValid()).isTrue();
        assertThat(properties.isFixedDelayValid()).isTrue();
    }

    @Test
    void enablingRequiresAPositiveRetentionWindow() {
        assertThat(new AnalyticsHousekeepingProperties(
                true, null, 100, Duration.ofHours(1))
                .isRetentionWindowValid()).isFalse();
        assertThat(new AnalyticsHousekeepingProperties(
                true, Duration.ZERO, 100, Duration.ofHours(1))
                .isRetentionWindowValid()).isFalse();
    }

    @Test
    void rejectsExplicitlyUnsafeBatchAndDelayValues() {
        try (var factory = Validation.buildDefaultValidatorFactory()) {
            var validator = factory.getValidator();

            assertThat(validator.validate(
                    new AnalyticsHousekeepingProperties(
                            true, Duration.ofDays(1), 0, Duration.ofHours(1))))
                    .extracting(violation ->
                            violation.getPropertyPath().toString())
                    .contains("batchSize");

            assertThat(validator.validate(
                    new AnalyticsHousekeepingProperties(
                            true, Duration.ofDays(1), 1, Duration.ZERO)))
                    .extracting(violation ->
                            violation.getPropertyPath().toString())
                    .contains("fixedDelayValid");
        }
    }
}

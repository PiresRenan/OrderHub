package io.github.piresrenan.orderhub.analytics.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;

import org.junit.jupiter.api.Test;

class AnalyticalRetentionPolicyTest {

    private static final Instant OCCURRED_AT =
            Instant.parse("2026-01-01T00:00:00Z");

    @Test
    void rejectsARetentionWindowThatWouldRetainAnalyticalDataForever() {
        // Why: analytical data without a bounded lifetime is the indiscriminate
        // accumulation OH-016 exists to prevent.
        // Covers: absent, zero and negative retention windows.
        // Prevents: a policy object that silently permits unbounded retention.

        assertThatThrownBy(() ->
                new AnalyticalRetentionPolicy(
                        null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() ->
                new AnalyticalRetentionPolicy(
                        Duration.ZERO))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() ->
                new AnalyticalRetentionPolicy(
                        Duration.ofDays(1).negated()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void derivesTheExpiryInstantFromTheOccurrenceTime() {
        // Why: expiry must be a deterministic function of when the operational
        // fact happened, not of when ingestion happened, so a delayed or
        // replayed ingestion cannot extend retention.
        // Covers: expiry derivation from occurrence time.
        // Prevents: re-ingestion silently prolonging the lifetime of a fact.

        var policy =
                new AnalyticalRetentionPolicy(
                        Duration.ofDays(400));

        assertThat(
                policy.expiresAt(
                        OCCURRED_AT))
                .isEqualTo(
                        Instant.parse("2027-02-05T00:00:00Z"));
    }

    @Test
    void treatsAFactAsExpiredFromTheExpiryInstantOnwards() {
        // Why: an ambiguous boundary makes purge behaviour untestable and lets
        // a fact survive its stated retention window.
        // Covers: the instant before expiry, the exact expiry instant and the
        // instant after.
        // Prevents: off-by-one retention that keeps data one evaluation longer
        // than the declared policy allows.

        var policy =
                new AnalyticalRetentionPolicy(
                        Duration.ofDays(400));

        var expiresAt =
                policy.expiresAt(
                        OCCURRED_AT);

        assertThat(
                policy.isExpired(
                        OCCURRED_AT,
                        expiresAt.minusNanos(1)))
                .as("A fact must be retained until its expiry instant")
                .isFalse();

        assertThat(
                policy.isExpired(
                        OCCURRED_AT,
                        expiresAt))
                .as("A fact must be expired at its expiry instant")
                .isTrue();

        assertThat(
                policy.isExpired(
                        OCCURRED_AT,
                        expiresAt.plusNanos(1)))
                .isTrue();
    }

    @Test
    void rejectsExpiryEvaluationWithoutBothInstants() {
        // Why: a null instant would otherwise silently produce a retention
        // decision from incomplete information.
        // Covers: mandatory occurrence and evaluation instants.
        // Prevents: purge decisions taken on missing time facts.

        var policy =
                new AnalyticalRetentionPolicy(
                        Duration.ofDays(400));

        assertThatThrownBy(() ->
                policy.expiresAt(
                        null))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() ->
                policy.isExpired(
                        null,
                        OCCURRED_AT))
                .isInstanceOf(IllegalArgumentException.class);

        assertThatThrownBy(() ->
                policy.isExpired(
                        OCCURRED_AT,
                        null))
                .isInstanceOf(IllegalArgumentException.class);
    }
}

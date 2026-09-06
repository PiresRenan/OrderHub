package io.github.piresrenan.orderhub.analytics.domain.model;

import java.time.Duration;
import java.time.Instant;

/**
 * Bounded lifetime of one class of analytical fact.
 *
 * <p>
 * Expiry is derived from the occurrence time of the operational fact rather
 * than from ingestion time, so a delayed or replayed ingestion cannot extend
 * how long analytical data is retained.
 * </p>
 *
 * <p>
 * A fact is expired from its expiry instant onwards.
 * </p>
 *
 * @param retentionWindow how long a fact may be retained after it occurred
 */
public record AnalyticalRetentionPolicy(
        Duration retentionWindow) {

    public AnalyticalRetentionPolicy {

        if (retentionWindow == null) {
            throw new IllegalArgumentException(
                    "Retention window is required");
        }

        if (retentionWindow.isZero()
                || retentionWindow.isNegative()) {

            throw new IllegalArgumentException(
                    "Retention window must be positive");
        }
    }

    public Instant expiresAt(
            Instant occurredAt) {

        if (occurredAt == null) {
            throw new IllegalArgumentException(
                    "Occurrence time is required");
        }

        return occurredAt.plus(
                retentionWindow);
    }

    public boolean isExpired(
            Instant occurredAt,
            Instant evaluatedAt) {

        if (evaluatedAt == null) {
            throw new IllegalArgumentException(
                    "Evaluation time is required");
        }

        return !evaluatedAt.isBefore(
                expiresAt(
                        occurredAt));
    }
}

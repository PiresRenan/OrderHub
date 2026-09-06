package io.github.piresrenan.orderhub.analytics.domain.model;

import java.time.Instant;
import java.util.UUID;
import java.util.regex.Pattern;

/**
 * One bounded analytical fact describing a privilege-significant workforce
 * action.
 *
 * <p>
 * The component set is deliberately closed. Subjects are pseudonymous, the
 * reason belongs to a bounded uppercase vocabulary, and there is no map,
 * collection or free-form payload component.
 * </p>
 *
 * <p>
 * The fact intentionally carries no operational Staff or User identifier, no
 * request-correlation metadata, no authority band and no personal data. It is
 * descriptive analytical evidence, never operational truth and never an
 * authorization input.
 * </p>
 *
 * @param sourceEventId identity of the originating operational event, used as
 *                      the idempotency key for at-least-once ingestion
 * @param tenantId      explicit Tenant scope
 * @param actorSubject  pseudonymous subject that performed the action
 * @param affectedSubject pseudonymous subject the action was directed at
 * @param action        closed analytical action vocabulary
 * @param outcome       closed analytical outcome vocabulary
 * @param reasonCode    bounded uppercase reason, absent when not applicable
 * @param occurredAt    occurrence time reported by the operational source
 * @param factType      bounded analytical fact vocabulary this contract
 *                      represents
 * @param schemaVersion explicit schema version carried by the fact, so a
 *                      persisted row stays interpretable after this contract
 *                      changes
 */
public record WorkforceAuthorityChangeFact(
        UUID sourceEventId,
        UUID tenantId,
        AnalyticalSubjectKey actorSubject,
        AnalyticalSubjectKey affectedSubject,
        WorkforceAuthorityChangeAction action,
        WorkforceAuthorityChangeOutcome outcome,
        String reasonCode,
        Instant occurredAt,
        AnalyticalFactType factType,
        int schemaVersion) {

    private static final Pattern REASON_CODE =
            Pattern.compile("^[A-Z][A-Z0-9_]{2,63}$");

    private static final int CURRENT_SCHEMA_VERSION = 1;

    /**
     * Builds the fact from its operational attributes, supplying the schema
     * identity intrinsic to this contract.
     *
     * <p>
     * This record represents {@code WORKFORCE_AUTHORITY_CHANGE} at schema
     * version {@code 1}, so a caller does not restate either fact.
     * </p>
     */
    public WorkforceAuthorityChangeFact(
            UUID sourceEventId,
            UUID tenantId,
            AnalyticalSubjectKey actorSubject,
            AnalyticalSubjectKey affectedSubject,
            WorkforceAuthorityChangeAction action,
            WorkforceAuthorityChangeOutcome outcome,
            String reasonCode,
            Instant occurredAt) {

        this(
                sourceEventId,
                tenantId,
                actorSubject,
                affectedSubject,
                action,
                outcome,
                reasonCode,
                occurredAt,
                AnalyticalFactType.WORKFORCE_AUTHORITY_CHANGE,
                CURRENT_SCHEMA_VERSION);
    }

    public WorkforceAuthorityChangeFact {

        if (sourceEventId == null) {
            throw new IllegalArgumentException(
                    "Source event ID is required");
        }

        if (tenantId == null) {
            throw new IllegalArgumentException(
                    "Tenant ID is required");
        }

        if (actorSubject == null) {
            throw new IllegalArgumentException(
                    "Actor analytical subject is required");
        }

        if (affectedSubject == null) {
            throw new IllegalArgumentException(
                    "Affected analytical subject is required");
        }

        if (action == null) {
            throw new IllegalArgumentException(
                    "Analytical action is required");
        }

        if (outcome == null) {
            throw new IllegalArgumentException(
                    "Analytical outcome is required");
        }

        if (occurredAt == null) {
            throw new IllegalArgumentException(
                    "Occurrence time is required");
        }

        if (factType == null) {
            throw new IllegalArgumentException(
                    "Analytical fact type is required");
        }

        if (schemaVersion <= 0) {
            throw new IllegalArgumentException(
                    "Analytical schema version must be positive");
        }

        if (reasonCode != null
                && !REASON_CODE.matcher(reasonCode).matches()) {

            throw new IllegalArgumentException(
                    "Analytical reason code must be bounded uppercase"
                            + " vocabulary");
        }
    }
}

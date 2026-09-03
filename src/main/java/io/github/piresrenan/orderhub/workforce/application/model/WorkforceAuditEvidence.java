package io.github.piresrenan.orderhub.workforce.application.model;

import java.util.UUID;
import java.util.regex.Pattern;

/**
 * Append-oriented evidence for one privilege-significant workforce fact.
 *
 * <p>
 * The database owns occurrence time. The application supplies only bounded,
 * typed identifiers and before/after organizational state.
 * </p>
 */
public record WorkforceAuditEvidence(
        UUID auditEventId,
        UUID tenantId,
        UUID actorStaffId,
        UUID affectedStaffId,
        WorkforceAuditActionType actionType,
        WorkforceAuditOutcome outcome,
        String reasonCode,
        UUID correlationId,
        WorkforceAuditState beforeState,
        WorkforceAuditState afterState) {

    private static final Pattern REASON_CODE =
            Pattern.compile("^[A-Z][A-Z0-9_]{2,63}$");

    public WorkforceAuditEvidence {

        if (auditEventId == null) {
            throw new IllegalArgumentException(
                    "Audit event ID is required");
        }

        if (tenantId == null) {
            throw new IllegalArgumentException(
                    "Tenant ID is required");
        }

        if (actorStaffId == null) {
            throw new IllegalArgumentException(
                    "Actor Staff ID is required");
        }

        if (affectedStaffId == null) {
            throw new IllegalArgumentException(
                    "Affected Staff ID is required");
        }

        if (actionType == null) {
            throw new IllegalArgumentException(
                    "Audit action type is required");
        }

        if (outcome == null) {
            throw new IllegalArgumentException(
                    "Audit outcome is required");
        }

        if (correlationId == null) {
            throw new IllegalArgumentException(
                    "Correlation ID is required");
        }

        if (beforeState == null) {
            throw new IllegalArgumentException(
                    "Before audit state is required");
        }

        if (afterState == null) {
            throw new IllegalArgumentException(
                    "After audit state is required");
        }

        if (reasonCode != null
                && !REASON_CODE.matcher(reasonCode).matches()) {

            throw new IllegalArgumentException(
                    "Audit reason code must be bounded uppercase vocabulary");
        }
    }
}

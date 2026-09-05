package io.github.piresrenan.orderhub.workforce.application.port.in;

import java.time.Instant;
import java.util.UUID;

import org.springframework.modulith.NamedInterface;

import io.github.piresrenan.orderhub.workforce.application.model.WorkforceAuditActionType;
import io.github.piresrenan.orderhub.workforce.application.model.WorkforceAuditOutcome;

/**
 * Bounded view of one committed workforce authority-change audit event, built
 * for analytical projection.
 *
 * <p>
 * The component set is deliberately narrower than the audit evidence workforce
 * stores. Correlation identity and organizational before/after state are
 * operational evidence with no analytical purpose here, so they never leave the
 * module through this contract.
 * </p>
 *
 * <p>
 * Action and outcome remain workforce's own bounded vocabulary. Translating
 * them into an analytical vocabulary belongs to the consumer, so workforce does
 * not acquire knowledge of how its evidence is analysed.
 * </p>
 *
 * <p>
 * The occurrence time is the one workforce already persisted for the event. It
 * is carried across unchanged so a consumer never has to substitute a time of
 * its own.
 * </p>
 *
 * @param tenantId         Tenant that owns the audit event
 * @param auditEventId     identity of the operational audit event
 * @param actorStaffId     Staff member that performed the action
 * @param affectedStaffId  Staff member the action was directed at
 * @param action           bounded workforce audit action vocabulary
 * @param outcome          bounded workforce audit outcome vocabulary
 * @param reasonCode       bounded reason, absent when not applicable
 * @param occurredAt       occurrence time workforce persisted for the event
 */
@NamedInterface("authority-change-analytics-source")
public record WorkforceAuthorityChangeAnalyticsSource(
        UUID tenantId,
        UUID auditEventId,
        UUID actorStaffId,
        UUID affectedStaffId,
        WorkforceAuditActionType action,
        WorkforceAuditOutcome outcome,
        String reasonCode,
        Instant occurredAt) {

    public WorkforceAuthorityChangeAnalyticsSource {

        if (tenantId == null) {
            throw new IllegalArgumentException(
                    "Tenant ID is required");
        }

        if (auditEventId == null) {
            throw new IllegalArgumentException(
                    "Audit event ID is required");
        }

        if (actorStaffId == null) {
            throw new IllegalArgumentException(
                    "Actor Staff ID is required");
        }

        if (affectedStaffId == null) {
            throw new IllegalArgumentException(
                    "Affected Staff ID is required");
        }

        if (action == null) {
            throw new IllegalArgumentException(
                    "Audit action type is required");
        }

        if (outcome == null) {
            throw new IllegalArgumentException(
                    "Audit outcome is required");
        }

        if (occurredAt == null) {
            throw new IllegalArgumentException(
                    "Occurrence time is required");
        }
    }
}

package io.github.piresrenan.orderhub.workforce.application.model;

import java.util.UUID;

import io.github.piresrenan.orderhub.authorization.domain.model.PermissionEnvelope;

/**
 * Caller-supplied facts for one privileged position change.
 *
 * <p>
 * Organizational before/after authority is deliberately absent. The application
 * resolves that state from workforce persistence rather than trusting caller
 * claims.
 * </p>
 */
public record PrivilegedPositionChangeCommand(
        UUID actorStaffId,
        UUID targetStaffId,
        UUID tenantId,
        UUID targetPositionId,
        boolean actorPrivilegedAuthorizationAllowed,
        PermissionEnvelope actorDelegationEnvelope,
        UUID auditEventId,
        UUID correlationId) {

    public PrivilegedPositionChangeCommand {

        if (actorStaffId == null) {
            throw new IllegalArgumentException(
                    "Actor Staff ID is required");
        }

        if (targetStaffId == null) {
            throw new IllegalArgumentException(
                    "Target Staff ID is required");
        }

        if (tenantId == null) {
            throw new IllegalArgumentException(
                    "Tenant ID is required");
        }

        if (targetPositionId == null) {
            throw new IllegalArgumentException(
                    "Target position ID is required");
        }

        if (actorDelegationEnvelope == null) {
            throw new IllegalArgumentException(
                    "Actor delegation envelope is required");
        }

        if (auditEventId == null) {
            throw new IllegalArgumentException(
                    "Audit event ID is required");
        }

        if (correlationId == null) {
            throw new IllegalArgumentException(
                    "Correlation ID is required");
        }
    }
}

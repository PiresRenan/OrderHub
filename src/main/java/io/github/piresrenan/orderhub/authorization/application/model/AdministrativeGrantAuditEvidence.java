package io.github.piresrenan.orderhub.authorization.application.model;

import java.util.UUID;

import io.github.piresrenan.orderhub.authorization.domain.model.AdministrativeScope;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionCode;

public record AdministrativeGrantAuditEvidence(
        UUID auditEventId,
        UUID actorUserId,
        UUID targetUserId,
        AdministrativeScope scope,
        PermissionCode permission,
        AdministrativeGrantAuditAction action,
        AdministrativeGrantAuditOutcome outcome,
        UUID correlationId,
        boolean beforeGranted,
        boolean afterGranted) {

    public AdministrativeGrantAuditEvidence {

        if (auditEventId == null) {
            throw new IllegalArgumentException(
                    "Administrative grant audit event id is required");
        }

        if (actorUserId == null) {
            throw new IllegalArgumentException(
                    "Administrative grant audit actor user id is required");
        }

        if (targetUserId == null) {
            throw new IllegalArgumentException(
                    "Administrative grant audit target user id is required");
        }

        if (scope == null) {
            throw new IllegalArgumentException(
                    "Administrative grant audit scope is required");
        }

        if (permission == null) {
            throw new IllegalArgumentException(
                    "Administrative grant audit permission is required");
        }

        if (action == null) {
            throw new IllegalArgumentException(
                    "Administrative grant audit action is required");
        }

        if (outcome == null) {
            throw new IllegalArgumentException(
                    "Administrative grant audit outcome is required");
        }

        if (correlationId == null) {
            throw new IllegalArgumentException(
                    "Administrative grant audit correlation id is required");
        }

        if (!permission.supportsAdministrativeScope(
                scope.type())) {

            throw new IllegalArgumentException(
                    "Administrative grant audit permission is incompatible with scope");
        }

        if (action
                == AdministrativeGrantAuditAction.GRANT_PERMISSION
                && !afterGranted) {

            throw new IllegalArgumentException(
                    "Administrative grant audit action is inconsistent with after state");
        }

        if (action
                == AdministrativeGrantAuditAction.REVOKE_PERMISSION
                && afterGranted) {

            throw new IllegalArgumentException(
                    "Administrative grant audit action is inconsistent with after state");
        }

        if (outcome
                == AdministrativeGrantAuditOutcome.APPLIED
                && beforeGranted == afterGranted) {

            throw new IllegalArgumentException(
                    "Applied administrative grant audit must represent a state transition");
        }

        if (outcome
                == AdministrativeGrantAuditOutcome.NO_CHANGE
                && beforeGranted != afterGranted) {

            throw new IllegalArgumentException(
                    "No-change administrative grant audit cannot represent a state transition");
        }
    }
}

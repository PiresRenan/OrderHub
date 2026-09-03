package io.github.piresrenan.orderhub.workforce.application.model;

import java.util.UUID;

import io.github.piresrenan.orderhub.workforce.domain.model.JobPosition;
import io.github.piresrenan.orderhub.workforce.domain.model.StaffProfile;

/**
 * Database-authoritative facts required to evaluate and execute one privileged
 * position change.
 */
public record WorkforcePositionChangeSnapshot(
        StaffProfile actorStaff,
        StaffProfile targetStaff,
        UUID targetDepartmentId,
        JobPosition actorPosition,
        JobPosition currentTargetPosition,
        JobPosition requestedTargetPosition) {

    public WorkforcePositionChangeSnapshot {

        if (actorStaff == null) {
            throw new IllegalArgumentException(
                    "Actor Staff is required");
        }

        if (targetStaff == null) {
            throw new IllegalArgumentException(
                    "Target Staff is required");
        }

        if (targetDepartmentId == null) {
            throw new IllegalArgumentException(
                    "Target Department ID is required");
        }

        if (actorPosition == null) {
            throw new IllegalArgumentException(
                    "Actor position is required");
        }

        if (currentTargetPosition == null) {
            throw new IllegalArgumentException(
                    "Current target position is required");
        }

        if (requestedTargetPosition == null) {
            throw new IllegalArgumentException(
                    "Requested target position is required");
        }
    }
}

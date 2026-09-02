package io.github.piresrenan.orderhub.workforce.domain.policy;

import io.github.piresrenan.orderhub.workforce.domain.model.JobPosition;
import io.github.piresrenan.orderhub.workforce.domain.model.PositionChange;
import io.github.piresrenan.orderhub.workforce.domain.model.PositionChangeType;
import io.github.piresrenan.orderhub.workforce.domain.model.StaffPlacement;
import io.github.piresrenan.orderhub.workforce.domain.model.StaffProfile;

/**
 * Classifies explicit organizational position changes.
 *
 * <p>
 * Classification is not authorization. A later application boundary must prove
 * the actor is allowed to perform the requested workforce authority mutation.
 * </p>
 */
public final class PositionChangePolicy {

    public PositionChange evaluate(
            StaffProfile staff,
            StaffPlacement currentPlacement,
            JobPosition currentPosition,
            JobPosition targetPosition) {

        if (staff == null) {
            throw new IllegalArgumentException(
                    "Staff is required");
        }

        if (currentPlacement == null) {
            throw new IllegalArgumentException(
                    "Current placement is required");
        }

        if (currentPosition == null
                || targetPosition == null) {

            throw new IllegalArgumentException(
                    "Current and target positions are required");
        }

        if (!staff.isActive()) {
            throw new IllegalArgumentException(
                    "Inactive Staff cannot change position");
        }

        var tenantId =
                staff.tenantId();

        if (!tenantId.equals(
                currentPlacement.tenantId())
                || !tenantId.equals(
                        currentPosition.tenantId())
                || !tenantId.equals(
                        targetPosition.tenantId())) {

            throw new IllegalArgumentException(
                    "Position change cannot cross Tenant scope");
        }

        if (!staff.staffId()
                .equals(
                        currentPlacement.staffId())) {

            throw new IllegalArgumentException(
                    "Current placement does not belong to Staff");
        }

        if (!currentPosition.positionId()
                .equals(
                        currentPlacement.positionId())) {

            throw new IllegalArgumentException(
                    "Current position does not match Staff placement");
        }

        if (currentPosition.positionId()
                .equals(
                        targetPosition.positionId())) {

            throw new IllegalArgumentException(
                    "Position change must target another position");
        }

        var type =
                classify(
                        currentPosition,
                        targetPosition);

        return new PositionChange(
                staff.staffId(),
                tenantId,
                currentPosition.positionId(),
                targetPosition.positionId(),
                currentPosition.authorityBand(),
                targetPosition.authorityBand(),
                currentPosition.permissionEnvelope(),
                targetPosition.permissionEnvelope(),
                type);
    }

    private PositionChangeType classify(
            JobPosition currentPosition,
            JobPosition targetPosition) {

        var targetAtLeastCurrent =
                targetPosition.authorityBand()
                        .isAtLeast(
                                currentPosition.authorityBand());

        var currentAtLeastTarget =
                currentPosition.authorityBand()
                        .isAtLeast(
                                targetPosition.authorityBand());

        if (targetAtLeastCurrent
                && !currentAtLeastTarget) {

            return PositionChangeType.PROMOTION;
        }

        if (currentAtLeastTarget
                && !targetAtLeastCurrent) {

            return PositionChangeType.DEMOTION;
        }

        return PositionChangeType.LATERAL;
    }
}

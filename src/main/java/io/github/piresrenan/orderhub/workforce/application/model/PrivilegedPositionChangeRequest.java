package io.github.piresrenan.orderhub.workforce.application.model;

import java.util.UUID;

import io.github.piresrenan.orderhub.workforce.domain.model.PositionChange;

/**
 * Explicit actor/target command facts for one privileged position mutation.
 *
 * <p>
 * The authorization kernel remains responsible for proving whether the actor
 * possesses the required privileged authority. This request records only that
 * already-resolved outcome together with opaque workforce identifiers.
 * </p>
 */
public record PrivilegedPositionChangeRequest(
        UUID actorStaffId,
        UUID targetStaffId,
        UUID tenantId,
        PositionChange positionChange,
        boolean actorPrivilegedAuthorizationAllowed) {

    public PrivilegedPositionChangeRequest {

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

        if (positionChange == null) {
            throw new IllegalArgumentException(
                    "Position change is required");
        }

        if (!targetStaffId.equals(
                positionChange.staffId())) {

            throw new IllegalArgumentException(
                    "Position change must belong to target Staff");
        }

        if (!tenantId.equals(
                positionChange.tenantId())) {

            throw new IllegalArgumentException(
                    "Position change must remain inside request Tenant");
        }
    }
}

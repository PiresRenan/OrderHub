package io.github.piresrenan.orderhub.workforce.domain.policy;

import io.github.piresrenan.orderhub.workforce.domain.model.EffectiveWorkforceAuthority;
import io.github.piresrenan.orderhub.workforce.domain.model.JobPosition;
import io.github.piresrenan.orderhub.workforce.domain.model.StaffPlacement;
import io.github.piresrenan.orderhub.workforce.domain.model.StaffProfile;

/**
 * Resolves the current organizational ceiling for a Staff relationship.
 */
public final class WorkforceAuthorityResolver {

    public EffectiveWorkforceAuthority resolve(
            StaffProfile staff,
            StaffPlacement placement,
            JobPosition position) {

        if (staff == null) {
            throw new IllegalArgumentException(
                    "Staff is required");
        }

        if (placement == null) {
            throw new IllegalArgumentException(
                    "Staff placement is required");
        }

        if (position == null) {
            throw new IllegalArgumentException(
                    "Job position is required");
        }

        if (!staff.tenantId()
                .equals(
                        placement.tenantId())
                || !staff.staffId()
                        .equals(
                                placement.staffId())) {

            throw new IllegalArgumentException(
                    "Staff placement does not belong to Staff");
        }

        if (!position.tenantId()
                .equals(
                        placement.tenantId())
                || !position.positionId()
                        .equals(
                                placement.positionId())) {

            throw new IllegalArgumentException(
                    "Job position does not match Staff placement");
        }

        if (!staff.isActive()) {

            return EffectiveWorkforceAuthority.none();
        }

        return EffectiveWorkforceAuthority.active(
                position.authorityBand(),
                position.permissionEnvelope());
    }
}

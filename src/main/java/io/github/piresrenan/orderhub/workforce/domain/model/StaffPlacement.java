package io.github.piresrenan.orderhub.workforce.domain.model;

import java.util.UUID;

/**
 * Current organizational placement of one StaffProfile.
 *
 * <p>
 * Placement binds Staff, Department and JobPosition inside one exact Tenant.
 * The relationship itself grants no permission.
 * </p>
 */
public final class StaffPlacement {

    private final UUID tenantId;

    private final UUID staffId;

    private final UUID departmentId;

    private final UUID positionId;

    private StaffPlacement(
            UUID tenantId,
            UUID staffId,
            UUID departmentId,
            UUID positionId) {

        this.tenantId = tenantId;
        this.staffId = staffId;
        this.departmentId = departmentId;
        this.positionId = positionId;
    }

    public static StaffPlacement assign(
            StaffProfile staff,
            Department department,
            JobPosition position) {

        if (staff == null) {
            throw new IllegalArgumentException(
                    "Staff is required");
        }

        if (department == null) {
            throw new IllegalArgumentException(
                    "Department is required");
        }

        if (position == null) {
            throw new IllegalArgumentException(
                    "Job position is required");
        }

        var tenantId =
                staff.tenantId();

        if (!tenantId.equals(
                department.tenantId())
                || !tenantId.equals(
                        position.tenantId())) {

            throw new IllegalArgumentException(
                    "Staff placement cannot cross Tenant scope");
        }

        return new StaffPlacement(
                tenantId,
                staff.staffId(),
                department.departmentId(),
                position.positionId());
    }

    public UUID tenantId() {

        return tenantId;
    }

    public UUID staffId() {

        return staffId;
    }

    public UUID departmentId() {

        return departmentId;
    }

    public UUID positionId() {

        return positionId;
    }
}

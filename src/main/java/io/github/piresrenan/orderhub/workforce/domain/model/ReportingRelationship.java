package io.github.piresrenan.orderhub.workforce.domain.model;

import java.util.UUID;

/**
 * One Tenant-scoped organizational supervisor -> subordinate edge.
 *
 * <p>
 * Reporting relationships are organizational facts and grant no permissions.
 * </p>
 */
public record ReportingRelationship(
        UUID tenantId,
        UUID supervisorStaffId,
        UUID subordinateStaffId) {

    public ReportingRelationship {

        if (tenantId == null) {
            throw new IllegalArgumentException(
                    "Tenant ID is required");
        }

        if (supervisorStaffId == null) {
            throw new IllegalArgumentException(
                    "Supervisor Staff ID is required");
        }

        if (subordinateStaffId == null) {
            throw new IllegalArgumentException(
                    "Subordinate Staff ID is required");
        }

        if (supervisorStaffId.equals(
                subordinateStaffId)) {

            throw new IllegalArgumentException(
                    "Staff cannot supervise itself");
        }
    }
}

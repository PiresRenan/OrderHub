package io.github.piresrenan.orderhub.workforce.domain.model;

import java.util.UUID;

/**
 * Tenant-scoped workforce relationship for one stable internal User.
 *
 * <p>
 * This model deliberately contains only opaque internal identifiers and
 * workforce state. Authentication-provider identity and employee PII are not
 * workforce authorization facts.
 * </p>
 */
public record StaffProfile(
        UUID staffId,
        UUID userId,
        UUID tenantId,
        StaffStatus status) {

    public StaffProfile {

        requireIdentifier(
                staffId,
                "Staff ID");

        requireIdentifier(
                userId,
                "User ID");

        requireIdentifier(
                tenantId,
                "Tenant ID");

        if (status == null) {
            throw new IllegalArgumentException(
                    "Staff status is required");
        }
    }

    public boolean isActive() {

        return status == StaffStatus.ACTIVE;
    }

    private static void requireIdentifier(
            UUID value,
            String label) {

        if (value == null) {
            throw new IllegalArgumentException(
                    label + " is required");
        }
    }
}

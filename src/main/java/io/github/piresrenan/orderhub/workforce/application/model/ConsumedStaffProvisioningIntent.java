package io.github.piresrenan.orderhub.workforce.application.model;

import java.util.UUID;

/**
 * Workforce-owned facts released only after a provisioning intent is
 * successfully consumed.
 *
 * <p>The one-time secret, its digest and provider identity are deliberately
 * absent from this application model.</p>
 */
public record ConsumedStaffProvisioningIntent(
        UUID intentId,
        UUID tenantId,
        UUID issuedByUserId,
        UUID departmentId,
        UUID positionId,
        String initialRoleCode,
        UUID correlationId) {

    public ConsumedStaffProvisioningIntent {

        requireIdentifier(
                intentId,
                "Provisioning intent ID");

        requireIdentifier(
                tenantId,
                "Tenant ID");

        requireIdentifier(
                issuedByUserId,
                "Issuing User ID");

        requireIdentifier(
                departmentId,
                "Department ID");

        requireIdentifier(
                positionId,
                "Position ID");

        requireIdentifier(
                correlationId,
                "Correlation ID");
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

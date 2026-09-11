package io.github.piresrenan.orderhub.workforce.application.model;

import java.util.UUID;

/**
 * Caller-supplied facts for issuing one Staff provisioning intent.
 *
 * <p>Credential material, durable intent identity, expiry and request
 * fingerprint are deliberately absent because they are application-owned
 * facts rather than caller authority.</p>
 */
@org.springframework.modulith.NamedInterface("staff-provisioning")
public record IssueStaffProvisioningIntentCommand(
        UUID tenantId,
        UUID issuedByUserId,
        UUID departmentId,
        UUID positionId,
        String initialRoleCode,
        UUID operationId,
        UUID correlationId) {

    public IssueStaffProvisioningIntentCommand {

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

        if (initialRoleCode != null
                && initialRoleCode.isBlank()) {

            throw new IllegalArgumentException(
                    "Initial role code must not be blank");
        }

        requireIdentifier(
                operationId,
                "Operation ID");

        requireIdentifier(
                correlationId,
                "Correlation ID");
    }

    /** Rejects missing internal selectors before persistence or canonical fingerprint construction. */
    private static void requireIdentifier(
            UUID value,
            String label) {

        if (value == null) {
            throw new IllegalArgumentException(
                    label + " is required");
        }
    }
}

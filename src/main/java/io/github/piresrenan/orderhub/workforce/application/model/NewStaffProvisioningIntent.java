package io.github.piresrenan.orderhub.workforce.application.model;

import java.time.OffsetDateTime;
import java.util.UUID;

/**
 * Durable facts required to establish one Staff provisioning intent.
 *
 * <p>The raw one-time provisioning secret is deliberately absent. Persistence
 * receives only its SHA-256 digest.</p>
 */
public record NewStaffProvisioningIntent(
        UUID intentId,
        UUID tenantId,
        byte[] secretDigest,
        UUID issuedByUserId,
        UUID departmentId,
        UUID positionId,
        String initialRoleCode,
        UUID operationId,
        byte[] requestFingerprint,
        OffsetDateTime expiresAt,
        UUID correlationId) {

    private static final int SHA_256_BYTES = 32;

    public NewStaffProvisioningIntent {

        requireIdentifier(
                intentId,
                "Provisioning intent ID");

        requireIdentifier(
                tenantId,
                "Tenant ID");

        requireDigest(
                secretDigest,
                "Provisioning secret digest");

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

        requireDigest(
                requestFingerprint,
                "Request fingerprint");

        if (expiresAt == null) {
            throw new IllegalArgumentException(
                    "Provisioning intent expiry is required");
        }

        requireIdentifier(
                correlationId,
                "Correlation ID");

        secretDigest =
                secretDigest.clone();

        requestFingerprint =
                requestFingerprint.clone();
    }

    @Override
    public byte[] secretDigest() {
        return secretDigest.clone();
    }

    @Override
    public byte[] requestFingerprint() {
        return requestFingerprint.clone();
    }

    private static void requireIdentifier(
            UUID value,
            String label) {

        if (value == null) {
            throw new IllegalArgumentException(
                    label + " is required");
        }
    }

    private static void requireDigest(
            byte[] value,
            String label) {

        if (value == null) {
            throw new IllegalArgumentException(
                    label + " is required");
        }

        if (value.length != SHA_256_BYTES) {
            throw new IllegalArgumentException(
                    label + " must contain 32 bytes");
        }
    }
}

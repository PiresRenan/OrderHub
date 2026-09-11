package io.github.piresrenan.orderhub.workforce.application.service;

import static java.nio.charset.StandardCharsets.UTF_8;

import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.Arrays;
import java.util.Base64;
import java.util.Objects;
import java.util.UUID;

import io.github.piresrenan.orderhub.workforce.application.model.IssueStaffProvisioningIntentCommand;
import io.github.piresrenan.orderhub.workforce.application.model.NewStaffProvisioningIntent;
import io.github.piresrenan.orderhub.workforce.application.model.StaffProvisioningIntentCreation;
import io.github.piresrenan.orderhub.workforce.application.model.StaffProvisioningIssuance;
import io.github.piresrenan.orderhub.workforce.application.port.in.IssueStaffProvisioningIntentUseCase;
import io.github.piresrenan.orderhub.workforce.application.port.out.StaffProvisioningIntentRepository;

/**
 * Issues one-time Staff provisioning credentials while keeping credential
 * material outside durable workforce state.
 *
 * <p>The service owns entropy generation, canonical request fingerprinting and
 * expiry. Persistence receives only the SHA-256 credential digest. A textual
 * credential is materialized only when this attempt actually establishes the
 * durable intent.</p>
 */
public final class StaffProvisioningIssuanceService
        implements IssueStaffProvisioningIntentUseCase {

    private static final int SECRET_BYTES = 32;

    private static final String FINGERPRINT_DOMAIN =
            "orderhub:workforce:staff-provisioning-intent:v1";

    private final StaffProvisioningIntentRepository repository;
    private final Clock clock;
    private final Duration ttl;
    private final SecureRandom secureRandom;

    /** Requires the supplied owner contracts; construction performs no lifecycle mutation or independent commit. */
    public StaffProvisioningIssuanceService(
            StaffProvisioningIntentRepository repository,
            Clock clock,
            Duration ttl,
            SecureRandom secureRandom) {

        this.repository =
                Objects.requireNonNull(
                        repository,
                        "repository");

        this.clock =
                Objects.requireNonNull(
                        clock,
                        "clock");

        this.ttl =
                requirePositiveTtl(
                        ttl);

        this.secureRandom =
                Objects.requireNonNull(
                        secureRandom,
                        "secureRandom");
    }

    /** Creates cryptographic proof material once and returns only bounded replay or fingerprint conflict afterward. */
    @Override
    public StaffProvisioningIssuance issue(
            IssueStaffProvisioningIntentCommand command) {

        Objects.requireNonNull(
                command,
                "command");

        var intentId =
                UUID.randomUUID();

        var expiresAt =
                OffsetDateTime.ofInstant(
                        clock.instant()
                                .plus(
                                        ttl),
                        ZoneOffset.UTC);

        var requestFingerprint =
                canonicalFingerprint(
                        command);

        var rawSecret =
                new byte[SECRET_BYTES];

        try {

            secureRandom.nextBytes(
                    rawSecret);

            var secretDigest =
                    sha256(
                            rawSecret);

            var intent =
                    new NewStaffProvisioningIntent(
                            intentId,
                            command.tenantId(),
                            secretDigest,
                            command.issuedByUserId(),
                            command.departmentId(),
                            command.positionId(),
                            command.initialRoleCode(),
                            command.operationId(),
                            requestFingerprint,
                            expiresAt,
                            command.correlationId());

            var creation =
                    repository.create(
                            intent);

            if (creation
                    instanceof StaffProvisioningIntentCreation.Created created) {

                var credential =
                        Base64.getUrlEncoder()
                                .withoutPadding()
                                .encodeToString(
                                        rawSecret);

                return new StaffProvisioningIssuance.Issued(
                        created.intentId(),
                        credential,
                        expiresAt);
            }

            if (creation
                    instanceof StaffProvisioningIntentCreation.Replay replay) {

                return new StaffProvisioningIssuance.Replay(
                        replay.intentId());
            }

            if (creation
                    instanceof StaffProvisioningIntentCreation.FingerprintConflict) {

                return new StaffProvisioningIssuance.FingerprintConflict();
            }

            throw new IllegalStateException(
                    "Unsupported Staff provisioning creation outcome");

        } finally {

            Arrays.fill(
                    rawSecret,
                    (byte) 0);
        }
    }

    /** Encodes only frozen semantic issuance facts; correlation and retry metadata do not change replay identity. */
    private byte[] canonicalFingerprint(
            IssueStaffProvisioningIntentCommand command) {

        var digest =
                sha256Digest();

        updateLengthPrefixed(
                digest,
                FINGERPRINT_DOMAIN.getBytes(
                        UTF_8));

        updateUuid(
                digest,
                command.tenantId());

        updateUuid(
                digest,
                command.issuedByUserId());

        updateUuid(
                digest,
                command.departmentId());

        updateUuid(
                digest,
                command.positionId());

        updateNullableUtf8(
                digest,
                command.initialRoleCode());

        return digest.digest();
    }

    /** Uses fixed-width UUID bytes so textual formatting cannot change the canonical fingerprint. */
    private void updateUuid(
            MessageDigest digest,
            UUID value) {

        var bytes =
                ByteBuffer.allocate(
                        16)
                        .putLong(
                                value.getMostSignificantBits())
                        .putLong(
                                value.getLeastSignificantBits())
                        .array();

        digest.update(
                bytes);
    }

    /** Distinguishes absence from an empty value when encoding the frozen operation fingerprint. */
    private void updateNullableUtf8(
            MessageDigest digest,
            String value) {

        if (value == null) {

            digest.update(
                    (byte) 0);

            return;
        }

        digest.update(
                (byte) 1);

        updateLengthPrefixed(
                digest,
                value.getBytes(
                        UTF_8));
    }

    /** Separates variable-length fields to prevent ambiguous concatenation in the operation fingerprint. */
    private void updateLengthPrefixed(
            MessageDigest digest,
            byte[] value) {

        digest.update(
                ByteBuffer.allocate(
                        Integer.BYTES)
                        .putInt(
                                value.length)
                        .array());

        digest.update(
                value);
    }

    /** Hashes canonical bytes with the required SHA-256 algorithm. */
    private byte[] sha256(
            byte[] value) {

        return sha256Digest()
                .digest(
                        value);
    }

    /** Requires SHA-256 availability; cryptographic setup failure is never treated as policy denial. */
    private MessageDigest sha256Digest() {

        try {

            return MessageDigest.getInstance(
                    "SHA-256");

        } catch (NoSuchAlgorithmException exception) {

            throw new IllegalStateException(
                    "SHA-256 is unavailable",
                    exception);
        }
    }

    /** Rejects absent or non-positive proof lifetime rather than supplying an implicit security default. */
    private static Duration requirePositiveTtl(
            Duration value) {

        if (value == null) {
            throw new IllegalArgumentException(
                    "Provisioning intent TTL is required");
        }

        if (value.isZero()
                || value.isNegative()) {

            throw new IllegalArgumentException(
                    "Provisioning intent TTL must be positive");
        }

        return value;
    }
}

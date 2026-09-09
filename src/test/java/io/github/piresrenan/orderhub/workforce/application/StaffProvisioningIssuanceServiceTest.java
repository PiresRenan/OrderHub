package io.github.piresrenan.orderhub.workforce.application;

import static java.nio.charset.StandardCharsets.UTF_8;
import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.InvocationTargetException;
import java.nio.ByteBuffer;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.security.SecureRandom;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.Base64;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.github.piresrenan.orderhub.workforce.application.model.ConsumedStaffProvisioningIntent;
import io.github.piresrenan.orderhub.workforce.application.model.IssueStaffProvisioningIntentCommand;
import io.github.piresrenan.orderhub.workforce.application.model.NewStaffProvisioningIntent;
import io.github.piresrenan.orderhub.workforce.application.model.StaffProvisioningIntentCreation;
import io.github.piresrenan.orderhub.workforce.application.model.StaffProvisioningIssuance;
import io.github.piresrenan.orderhub.workforce.application.port.in.IssueStaffProvisioningIntentUseCase;
import io.github.piresrenan.orderhub.workforce.application.port.out.StaffProvisioningIntentRepository;

class StaffProvisioningIssuanceServiceTest {

    private static final Instant NOW =
            Instant.parse(
                    "2030-05-06T12:30:00Z");

    private static final Clock CLOCK =
            Clock.fixed(
                    NOW,
                    ZoneOffset.UTC);

    private static final Duration TTL =
            Duration.ofMinutes(
                    30);

    private static final byte[] FINGERPRINT_DOMAIN =
            "orderhub:workforce:staff-provisioning-intent:v1"
                    .getBytes(
                            UTF_8);

    @Test
    void createdOperationIssuesExactlyOneBase64UrlCredentialAndPersistsOnlyItsDigest() {

        var rawSecret =
                fixedBytes(
                        (byte) 0x11);

        var random =
                new TrackingSecureRandom(
                        rawSecret);

        var repository =
                new DeterministicCreationRepository();

        var service =
                service(
                        repository,
                        random);

        var command =
                command(
                        UUID.fromString(
                                "30000000-0000-4000-8000-000000000101"),
                        "TENANT_STAFF");

        var outcome =
                service.issue(
                        command);

        assertThat(outcome)
                .isInstanceOf(
                        StaffProvisioningIssuance.Issued.class);

        var issued =
                (StaffProvisioningIssuance.Issued) outcome;

        var persisted =
                repository.attempts()
                        .getFirst();

        var expectedCredential =
                Base64.getUrlEncoder()
                        .withoutPadding()
                        .encodeToString(
                                rawSecret);

        assertThat(issued.intentId())
                .isEqualTo(
                        persisted.intentId());

        assertThat(issued.credential())
                .isEqualTo(
                        expectedCredential);

        assertThat(issued.credential())
                .hasSize(
                        43);

        assertThat(issued.credential())
                .doesNotContain(
                        "=");

        assertThat(
                Base64.getUrlDecoder()
                        .decode(
                                issued.credential()))
                .containsExactly(
                        rawSecret);

        assertThat(persisted.secretDigest())
                .containsExactly(
                        sha256(
                                rawSecret));

        assertThat(persisted.requestFingerprint())
                .containsExactly(
                        canonicalFingerprint(
                                command));

        assertThat(persisted.expiresAt())
                .isEqualTo(
                        OffsetDateTime.ofInstant(
                                NOW.plus(
                                        TTL),
                                ZoneOffset.UTC));

        assertThat(persisted.tenantId())
                .isEqualTo(
                        command.tenantId());

        assertThat(persisted.issuedByUserId())
                .isEqualTo(
                        command.issuedByUserId());

        assertThat(persisted.departmentId())
                .isEqualTo(
                        command.departmentId());

        assertThat(persisted.positionId())
                .isEqualTo(
                        command.positionId());

        assertThat(persisted.initialRoleCode())
                .isEqualTo(
                        command.initialRoleCode());

        assertThat(persisted.operationId())
                .isEqualTo(
                        command.operationId());

        assertThat(persisted.correlationId())
                .isEqualTo(
                        command.correlationId());

        assertThat(random.allTargetsZeroized())
                .as(
                        "mutable raw secret buffer must be cleared after issuance")
                .isTrue();
    }

    @Test
    void replayRecognizesSameCanonicalRequestWithoutReturningAnotherCredential() {

        var random =
                new TrackingSecureRandom(
                        fixedBytes(
                                (byte) 0x21),
                        fixedBytes(
                                (byte) 0x22));

        var repository =
                new DeterministicCreationRepository();

        var service =
                service(
                        repository,
                        random);

        var firstCommand =
                command(
                        UUID.fromString(
                                "30000000-0000-4000-8000-000000000201"),
                        "TENANT_STAFF");

        var retryCommand =
                new IssueStaffProvisioningIntentCommand(
                        firstCommand.tenantId(),
                        firstCommand.issuedByUserId(),
                        firstCommand.departmentId(),
                        firstCommand.positionId(),
                        firstCommand.initialRoleCode(),
                        firstCommand.operationId(),
                        UUID.fromString(
                                "30000000-0000-4000-8000-000000000202"));

        var first =
                service.issue(
                        firstCommand);

        var retry =
                service.issue(
                        retryCommand);

        assertThat(first)
                .isInstanceOf(
                        StaffProvisioningIssuance.Issued.class);

        assertThat(retry)
                .isInstanceOf(
                        StaffProvisioningIssuance.Replay.class);

        var issued =
                (StaffProvisioningIssuance.Issued) first;

        var replay =
                (StaffProvisioningIssuance.Replay) retry;

        assertThat(replay.intentId())
                .isEqualTo(
                        issued.intentId());

        assertThat(repository.attempts())
                .hasSize(
                        2);

        var firstAttempt =
                repository.attempts()
                        .get(0);

        var retryAttempt =
                repository.attempts()
                        .get(1);

        assertThat(retryAttempt.requestFingerprint())
                .containsExactly(
                        firstAttempt.requestFingerprint());

        assertThat(retryAttempt.secretDigest())
                .isNotEqualTo(
                        firstAttempt.secretDigest());

        assertThat(retryAttempt.correlationId())
                .isNotEqualTo(
                        firstAttempt.correlationId());

        assertThat(random.allTargetsZeroized())
                .as(
                        "replay-generated ephemeral secret bytes must be cleared")
                .isTrue();

        assertThat(
                StaffProvisioningIssuance.Replay.class
                        .getRecordComponents())
                .extracting(
                        component -> component.getName())
                .containsExactly(
                        "intentId");
    }

    @Test
    void changedCanonicalRequestProducesFingerprintConflictWithoutCredential() {

        var random =
                new TrackingSecureRandom(
                        fixedBytes(
                                (byte) 0x31),
                        fixedBytes(
                                (byte) 0x32));

        var repository =
                new DeterministicCreationRepository();

        var service =
                service(
                        repository,
                        random);

        var firstCommand =
                command(
                        UUID.fromString(
                                "30000000-0000-4000-8000-000000000301"),
                        "TENANT_STAFF");

        var conflictingCommand =
                new IssueStaffProvisioningIntentCommand(
                        firstCommand.tenantId(),
                        firstCommand.issuedByUserId(),
                        firstCommand.departmentId(),
                        firstCommand.positionId(),
                        "TENANT_MANAGER",
                        firstCommand.operationId(),
                        UUID.fromString(
                                "30000000-0000-4000-8000-000000000302"));

        var first =
                service.issue(
                        firstCommand);

        var conflict =
                service.issue(
                        conflictingCommand);

        assertThat(first)
                .isInstanceOf(
                        StaffProvisioningIssuance.Issued.class);

        assertThat(conflict)
                .isInstanceOf(
                        StaffProvisioningIssuance.FingerprintConflict.class);

        assertThat(repository.attempts())
                .hasSize(
                        2);

        assertThat(
                repository.attempts()
                        .get(0)
                        .requestFingerprint())
                .isNotEqualTo(
                        repository.attempts()
                                .get(1)
                                .requestFingerprint());

        assertThat(random.allTargetsZeroized())
                .as(
                        "conflicting ephemeral secret bytes must be cleared")
                .isTrue();

        assertThat(
                StaffProvisioningIssuance.FingerprintConflict.class
                        .getRecordComponents())
                .isEmpty();
    }

    private IssueStaffProvisioningIntentUseCase service(
            StaffProvisioningIntentRepository repository,
            SecureRandom secureRandom) {

        try {

            var type =
                    Class.forName(
                            "io.github.piresrenan.orderhub.workforce.application.service."
                                    + "StaffProvisioningIssuanceService");

            var constructor =
                    type.getConstructor(
                            StaffProvisioningIntentRepository.class,
                            Clock.class,
                            Duration.class,
                            SecureRandom.class);

            var instance =
                    constructor.newInstance(
                            repository,
                            CLOCK,
                            TTL,
                            secureRandom);

            if (!(instance instanceof IssueStaffProvisioningIntentUseCase useCase)) {

                throw new AssertionError(
                        "Staff provisioning issuance service "
                                + "does not implement its application input port");
            }

            return useCase;

        } catch (ClassNotFoundException exception) {

            throw new AssertionError(
                    "Staff provisioning issuance application service is missing",
                    exception);

        } catch (NoSuchMethodException exception) {

            throw new AssertionError(
                    "Staff provisioning issuance constructor contract is missing",
                    exception);

        } catch (InvocationTargetException exception) {

            var cause =
                    exception.getCause();

            if (cause instanceof RuntimeException runtimeException) {
                throw runtimeException;
            }

            if (cause instanceof Error error) {
                throw error;
            }

            throw new AssertionError(
                    "Staff provisioning issuance service construction failed",
                    cause);

        } catch (ReflectiveOperationException exception) {

            throw new AssertionError(
                    "Staff provisioning issuance service cannot be constructed",
                    exception);
        }
    }

    private IssueStaffProvisioningIntentCommand command(
            UUID correlationId,
            String roleCode) {

        return new IssueStaffProvisioningIntentCommand(
                UUID.fromString(
                        "30000000-0000-4000-8000-000000000001"),
                UUID.fromString(
                        "30000000-0000-4000-8000-000000000002"),
                UUID.fromString(
                        "30000000-0000-4000-8000-000000000003"),
                UUID.fromString(
                        "30000000-0000-4000-8000-000000000004"),
                roleCode,
                UUID.fromString(
                        "30000000-0000-4000-8000-000000000005"),
                correlationId);
    }

    private byte[] canonicalFingerprint(
            IssueStaffProvisioningIntentCommand command) {

        var digest =
                sha256Digest();

        updateLengthPrefixed(
                digest,
                FINGERPRINT_DOMAIN);

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

    private byte[] sha256(
            byte[] value) {

        return sha256Digest()
                .digest(
                        value);
    }

    private MessageDigest sha256Digest() {

        try {

            return MessageDigest.getInstance(
                    "SHA-256");

        } catch (NoSuchAlgorithmException exception) {

            throw new AssertionError(
                    "SHA-256 must be available in the Java runtime",
                    exception);
        }
    }

    private byte[] fixedBytes(
            byte value) {

        var bytes =
                new byte[32];

        Arrays.fill(
                bytes,
                value);

        return bytes;
    }

    private static final class DeterministicCreationRepository
            implements StaffProvisioningIntentRepository {

        private final List<NewStaffProvisioningIntent> attempts =
                new ArrayList<>();

        private NewStaffProvisioningIntent durable;

        @Override
        public StaffProvisioningIntentCreation create(
                NewStaffProvisioningIntent intent) {

            attempts.add(
                    intent);

            if (durable == null) {

                durable =
                        intent;

                return new StaffProvisioningIntentCreation.Created(
                        intent.intentId());
            }

            if (!durable.tenantId()
                    .equals(
                            intent.tenantId())
                    || !durable.operationId()
                            .equals(
                                    intent.operationId())) {

                throw new AssertionError(
                        "Test repository supports one durable operation identity");
            }

            if (Arrays.equals(
                    durable.requestFingerprint(),
                    intent.requestFingerprint())) {

                return new StaffProvisioningIntentCreation.Replay(
                        durable.intentId());
            }

            return new StaffProvisioningIntentCreation.FingerprintConflict();
        }

        @Override
        public Optional<ConsumedStaffProvisioningIntent> consumePending(
                byte[] secretDigest,
                OffsetDateTime consumedAt) {

            throw new UnsupportedOperationException(
                    "Consumption is outside issuance specification");
        }

        @Override
        public boolean cancelPending(
                UUID tenantId,
                UUID intentId,
                OffsetDateTime cancelledAt) {

            throw new UnsupportedOperationException(
                    "Cancellation is outside issuance specification");
        }

        List<NewStaffProvisioningIntent> attempts() {

            return List.copyOf(
                    attempts);
        }
    }

    private static final class TrackingSecureRandom
            extends SecureRandom {

        private static final long serialVersionUID = 1L;

        private final List<byte[]> templates;
        private final List<byte[]> targets =
                new ArrayList<>();

        private int index;

        TrackingSecureRandom(
                byte[]... templates) {

            this.templates =
                    Arrays.stream(
                            templates)
                            .map(
                                    byte[]::clone)
                            .toList();
        }

        @Override
        public void nextBytes(
                byte[] bytes) {

            if (index >= templates.size()) {
                throw new AssertionError(
                        "Unexpected provisioning entropy request");
            }

            var template =
                    templates.get(
                            index++);

            if (bytes.length != template.length) {
                throw new AssertionError(
                        "Expected exactly "
                                + template.length
                                + " bytes of provisioning entropy but got "
                                + bytes.length);
            }

            System.arraycopy(
                    template,
                    0,
                    bytes,
                    0,
                    template.length);

            targets.add(
                    bytes);
        }

        boolean allTargetsZeroized() {

            if (targets.isEmpty()) {
                return false;
            }

            for (var target : targets) {

                for (var value : target) {

                    if (value != 0) {
                        return false;
                    }
                }
            }

            return true;
        }
    }
}

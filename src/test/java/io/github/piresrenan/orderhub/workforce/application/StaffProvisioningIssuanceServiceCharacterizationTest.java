package io.github.piresrenan.orderhub.workforce.application;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import io.github.piresrenan.orderhub.workforce.application.model.ConsumedStaffProvisioningIntent;
import io.github.piresrenan.orderhub.workforce.application.model.IssueStaffProvisioningIntentCommand;
import io.github.piresrenan.orderhub.workforce.application.model.NewStaffProvisioningIntent;
import io.github.piresrenan.orderhub.workforce.application.model.StaffProvisioningIntentCreation;
import io.github.piresrenan.orderhub.workforce.application.model.StaffProvisioningIssuance;
import io.github.piresrenan.orderhub.workforce.application.port.out.StaffProvisioningIntentRepository;
import io.github.piresrenan.orderhub.workforce.application.service.StaffProvisioningIssuanceService;

/**
 * Why: Staff onboarding is a privileged one-time workflow rather than a caller-asserted identity.
 * Covers: The intent, issuance, consumption or composition boundary exercised by this suite.
 * Prevents: Replay corruption, authority bypass and incomplete atomic provisioning behavior.
 */
class StaffProvisioningIssuanceServiceCharacterizationTest {

    private static final Clock CLOCK =
            Clock.fixed(
                    Instant.parse(
                            "2030-05-06T12:30:00Z"),
                    ZoneOffset.UTC);

    private static final Duration TTL =
            Duration.ofMinutes(
                    30);

    @Test
    void rejectsMissingZeroAndNegativeTtlBeforeEntropyOrPersistence() {

        var repository =
                new AlwaysCreateRepository();

        var random =
                new TrackingSecureRandom();

        assertThatThrownBy(
                () -> new StaffProvisioningIssuanceService(
                        repository,
                        CLOCK,
                        null,
                        random))
                .isInstanceOf(
                        IllegalArgumentException.class)
                .hasMessage(
                        "Provisioning intent TTL is required");

        assertThatThrownBy(
                () -> new StaffProvisioningIssuanceService(
                        repository,
                        CLOCK,
                        Duration.ZERO,
                        random))
                .isInstanceOf(
                        IllegalArgumentException.class)
                .hasMessage(
                        "Provisioning intent TTL must be positive");

        assertThatThrownBy(
                () -> new StaffProvisioningIssuanceService(
                        repository,
                        CLOCK,
                        Duration.ofSeconds(
                                -1),
                        random))
                .isInstanceOf(
                        IllegalArgumentException.class)
                .hasMessage(
                        "Provisioning intent TTL must be positive");

        assertThat(repository.attempts())
                .isEmpty();

        assertThat(random.requestCount())
                .isZero();
    }

    @Test
    void nullInitialRoleCodeIsIssuableAndDistinctFromConcreteRole() {

        var random =
                new TrackingSecureRandom(
                        fixedBytes(
                                (byte) 0x11),
                        fixedBytes(
                                (byte) 0x12));

        var repository =
                new IdempotentRepository();

        var service =
                service(
                        repository,
                        random);

        var operationId =
                uuid(
                        "40000000-0000-4000-8000-000000000101");

        var first =
                service.issue(
                        command(
                                issuerA(),
                                null,
                                operationId,
                                uuid(
                                        "40000000-0000-4000-8000-000000000102")));

        var second =
                service.issue(
                        command(
                                issuerA(),
                                "TENANT_STAFF",
                                operationId,
                                uuid(
                                        "40000000-0000-4000-8000-000000000103")));

        assertThat(first)
                .isInstanceOf(
                        StaffProvisioningIssuance.Issued.class);

        assertThat(second)
                .isInstanceOf(
                        StaffProvisioningIssuance.FingerprintConflict.class);

        assertThat(repository.attempts())
                .hasSize(
                        2);

        assertThat(
                repository.attempts()
                        .get(0)
                        .initialRoleCode())
                .isNull();

        assertThat(
                repository.attempts()
                        .get(0)
                        .requestFingerprint())
                .isNotEqualTo(
                        repository.attempts()
                                .get(1)
                                .requestFingerprint());

        assertThat(random.allTargetsZeroized())
                .isTrue();
    }

    @Test
    void issuerIdentityIsPartOfCanonicalFingerprint() {

        var random =
                new TrackingSecureRandom(
                        fixedBytes(
                                (byte) 0x21),
                        fixedBytes(
                                (byte) 0x22));

        var repository =
                new IdempotentRepository();

        var service =
                service(
                        repository,
                        random);

        var operationId =
                uuid(
                        "40000000-0000-4000-8000-000000000201");

        var first =
                service.issue(
                        command(
                                issuerA(),
                                "TENANT_STAFF",
                                operationId,
                                uuid(
                                        "40000000-0000-4000-8000-000000000202")));

        var second =
                service.issue(
                        command(
                                issuerB(),
                                "TENANT_STAFF",
                                operationId,
                                uuid(
                                        "40000000-0000-4000-8000-000000000203")));

        assertThat(first)
                .isInstanceOf(
                        StaffProvisioningIssuance.Issued.class);

        assertThat(second)
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
                .isTrue();
    }

    @Test
    void operationIdentityIsExcludedFromCanonicalRequestFingerprint() {

        var random =
                new TrackingSecureRandom(
                        fixedBytes(
                                (byte) 0x31),
                        fixedBytes(
                                (byte) 0x32));

        var repository =
                new AlwaysCreateRepository();

        var service =
                service(
                        repository,
                        random);

        var correlationId =
                uuid(
                        "40000000-0000-4000-8000-000000000301");

        service.issue(
                command(
                        issuerA(),
                        "TENANT_STAFF",
                        uuid(
                                "40000000-0000-4000-8000-000000000302"),
                        correlationId));

        service.issue(
                command(
                        issuerA(),
                        "TENANT_STAFF",
                        uuid(
                                "40000000-0000-4000-8000-000000000303"),
                        correlationId));

        assertThat(repository.attempts())
                .hasSize(
                        2);

        assertThat(
                repository.attempts()
                        .get(0)
                        .operationId())
                .isNotEqualTo(
                        repository.attempts()
                                .get(1)
                                .operationId());

        assertThat(
                repository.attempts()
                        .get(0)
                        .requestFingerprint())
                .containsExactly(
                        repository.attempts()
                                .get(1)
                                .requestFingerprint());

        assertThat(random.allTargetsZeroized())
                .isTrue();
    }

    @Test
    void correlationIdentityIsExcludedFromCanonicalRequestFingerprint() {

        var random =
                new TrackingSecureRandom(
                        fixedBytes(
                                (byte) 0x41),
                        fixedBytes(
                                (byte) 0x42));

        var repository =
                new IdempotentRepository();

        var service =
                service(
                        repository,
                        random);

        var operationId =
                uuid(
                        "40000000-0000-4000-8000-000000000401");

        var first =
                service.issue(
                        command(
                                issuerA(),
                                "TENANT_STAFF",
                                operationId,
                                uuid(
                                        "40000000-0000-4000-8000-000000000402")));

        var second =
                service.issue(
                        command(
                                issuerA(),
                                "TENANT_STAFF",
                                operationId,
                                uuid(
                                        "40000000-0000-4000-8000-000000000403")));

        assertThat(first)
                .isInstanceOf(
                        StaffProvisioningIssuance.Issued.class);

        assertThat(second)
                .isInstanceOf(
                        StaffProvisioningIssuance.Replay.class);

        assertThat(repository.attempts())
                .hasSize(
                        2);

        assertThat(
                repository.attempts()
                        .get(0)
                        .correlationId())
                .isNotEqualTo(
                        repository.attempts()
                                .get(1)
                                .correlationId());

        assertThat(
                repository.attempts()
                        .get(0)
                        .requestFingerprint())
                .containsExactly(
                        repository.attempts()
                                .get(1)
                                .requestFingerprint());

        assertThat(random.allTargetsZeroized())
                .isTrue();
    }

    @Test
    void repositoryFailureStillZeroizesRawEntropyAndPropagatesFailure() {

        var rawSecret =
                fixedBytes(
                        (byte) 0x51);

        var random =
                new TrackingSecureRandom(
                        rawSecret);

        var failure =
                new IllegalStateException(
                        "synthetic persistence failure");

        var repository =
                new ThrowingRepository(
                        failure);

        var service =
                service(
                        repository,
                        random);

        assertThatThrownBy(
                () -> service.issue(
                        command(
                                issuerA(),
                                "TENANT_STAFF",
                                uuid(
                                        "40000000-0000-4000-8000-000000000501"),
                                uuid(
                                        "40000000-0000-4000-8000-000000000502"))))
                .isSameAs(
                        failure);

        assertThat(repository.attempt())
                .isNotNull();

        assertThat(
                repository.attempt()
                        .secretDigest())
                .containsExactly(
                        sha256(
                                rawSecret));

        assertThat(random.allTargetsZeroized())
                .as(
                        "raw entropy must be cleared even when persistence fails")
                .isTrue();
    }

    private StaffProvisioningIssuanceService service(
            StaffProvisioningIntentRepository repository,
            SecureRandom secureRandom) {

        return new StaffProvisioningIssuanceService(
                repository,
                CLOCK,
                TTL,
                secureRandom);
    }

    private IssueStaffProvisioningIntentCommand command(
            UUID issuedByUserId,
            String roleCode,
            UUID operationId,
            UUID correlationId) {

        return new IssueStaffProvisioningIntentCommand(
                uuid(
                        "40000000-0000-4000-8000-000000000001"),
                issuedByUserId,
                uuid(
                        "40000000-0000-4000-8000-000000000003"),
                uuid(
                        "40000000-0000-4000-8000-000000000004"),
                roleCode,
                operationId,
                correlationId);
    }

    private UUID issuerA() {

        return uuid(
                "40000000-0000-4000-8000-000000000011");
    }

    private UUID issuerB() {

        return uuid(
                "40000000-0000-4000-8000-000000000012");
    }

    private UUID uuid(
            String value) {

        return UUID.fromString(
                value);
    }

    private static byte[] fixedBytes(
            byte value) {

        var bytes =
                new byte[32];

        Arrays.fill(
                bytes,
                value);

        return bytes;
    }

    private static byte[] sha256(
            byte[] value) {

        try {

            return MessageDigest.getInstance(
                    "SHA-256")
                    .digest(
                            value);

        } catch (NoSuchAlgorithmException exception) {

            throw new AssertionError(
                    "SHA-256 must exist",
                    exception);
        }
    }

    private static class AlwaysCreateRepository
            implements StaffProvisioningIntentRepository {

        protected final List<NewStaffProvisioningIntent> attempts =
                new ArrayList<>();

        @Override
        public StaffProvisioningIntentCreation create(
                NewStaffProvisioningIntent intent) {

            attempts.add(
                    intent);

            return new StaffProvisioningIntentCreation.Created(
                    intent.intentId());
        }

        @Override
        public Optional<ConsumedStaffProvisioningIntent> consumePending(
                byte[] secretDigest,
                OffsetDateTime consumedAt) {

            throw new UnsupportedOperationException();
        }

        @Override
        public boolean cancelPending(
                UUID tenantId,
                UUID intentId,
                OffsetDateTime cancelledAt) {

            throw new UnsupportedOperationException();
        }

        List<NewStaffProvisioningIntent> attempts() {

            return List.copyOf(
                    attempts);
        }
    }

    private static final class IdempotentRepository
            extends AlwaysCreateRepository {

        private NewStaffProvisioningIntent durable;

        @Override
        public StaffProvisioningIntentCreation create(
                NewStaffProvisioningIntent intent) {

            super.attempts.add(
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
                        "Characterization repository supports one operation identity");
            }

            if (Arrays.equals(
                    durable.requestFingerprint(),
                    intent.requestFingerprint())) {

                return new StaffProvisioningIntentCreation.Replay(
                        durable.intentId());
            }

            return new StaffProvisioningIntentCreation.FingerprintConflict();
        }
    }

    private static final class ThrowingRepository
            implements StaffProvisioningIntentRepository {

        private final RuntimeException failure;

        private NewStaffProvisioningIntent attempt;

        ThrowingRepository(
                RuntimeException failure) {

            this.failure =
                    failure;
        }

        @Override
        public StaffProvisioningIntentCreation create(
                NewStaffProvisioningIntent intent) {

            attempt =
                    intent;

            throw failure;
        }

        @Override
        public Optional<ConsumedStaffProvisioningIntent> consumePending(
                byte[] secretDigest,
                OffsetDateTime consumedAt) {

            throw new UnsupportedOperationException();
        }

        @Override
        public boolean cancelPending(
                UUID tenantId,
                UUID intentId,
                OffsetDateTime cancelledAt) {

            throw new UnsupportedOperationException();
        }

        NewStaffProvisioningIntent attempt() {

            return attempt;
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
                        "Unexpected entropy request");
            }

            var template =
                    templates.get(
                            index++);

            if (bytes.length != template.length) {
                throw new AssertionError(
                        "Expected "
                                + template.length
                                + " entropy bytes but got "
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

        int requestCount() {

            return index;
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

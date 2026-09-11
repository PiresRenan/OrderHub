package io.github.piresrenan.orderhub.workforce.adapter.out.persistence.postgresql;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import io.github.piresrenan.orderhub.workforce.application.model.NewStaffProvisioningIntent;
import io.github.piresrenan.orderhub.workforce.application.model.StaffProvisioningIntentCreation;
import io.github.piresrenan.orderhub.workforce.application.port.out.StaffProvisioningIntentRepository;

/**
 * Why: Staff onboarding is a privileged one-time workflow rather than a caller-asserted identity.
 * Covers: The intent, issuance, consumption or composition boundary exercised by this suite.
 * Prevents: Replay corruption, authority bypass and incomplete atomic provisioning behavior.
 */
@Testcontainers
class PostgreSqlStaffProvisioningIntentCreationConcurrencyTest {

    private static final int RACE_ATTEMPTS = 32;

    private static final DockerImageName POSTGRES_IMAGE =
            DockerImageName.parse(
                    "postgres:18.6-trixie@sha256:"
                            + "4ef4dbc939d61acea57712655ddb4b4ab27419c913f94cca0cd57cb3ea3c2280")
                    .asCompatibleSubstituteFor(
                            "postgres");

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(
                    POSTGRES_IMAGE)
                    .withDatabaseName(
                            "orderhub_test")
                    .withUsername(
                            "orderhub_test")
                    .withPassword(
                            "synthetic-test-password");

    private static JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void migrateSchema() {

        var dataSource =
                new DriverManagerDataSource(
                        POSTGRES.getJdbcUrl(),
                        POSTGRES.getUsername(),
                        POSTGRES.getPassword());

        Flyway.configure()
                .dataSource(
                        dataSource)
                .locations(
                        "classpath:db/migration")
                .load()
                .migrate();

        jdbcTemplate =
                new JdbcTemplate(
                        dataSource);
    }

    @BeforeEach
    void cleanState() {

        jdbcTemplate.update(
                """
                TRUNCATE TABLE
                    workforce.staff_provisioning_intents,
                    workforce.job_positions,
                    workforce.departments
                CASCADE
                """);
    }

    @Test
    void concurrentSameOperationAndFingerprintProduceOneCreatedAndOneReplay()
            throws Exception {

        var executor =
                Executors.newFixedThreadPool(
                        2);

        try {

            for (var attempt = 0;
                    attempt < RACE_ATTEMPTS;
                    attempt++) {

                var fixture =
                        fixture();

                var fingerprint =
                        fixedBytes(
                                0x30,
                                attempt);

                var firstIntent =
                        intent(
                                fixture,
                                fixedUuid(
                                        0x11,
                                        attempt),
                                fixedBytes(
                                        0x11,
                                        attempt),
                                fingerprint,
                                fixedUuid(
                                        0x12,
                                        attempt));

                var secondIntent =
                        intent(
                                fixture,
                                fixedUuid(
                                        0x21,
                                        attempt),
                                fixedBytes(
                                        0x21,
                                        attempt),
                                fingerprint,
                                fixedUuid(
                                        0x22,
                                        attempt));

                var repository =
                        repository();

                var result =
                        race(
                                executor,
                                () -> repository.create(
                                        firstIntent),
                                () -> repository.create(
                                        secondIntent));

                var createdCount =
                        createdCount(
                                result);

                var replayCount =
                        replayCount(
                                result);

                var conflictCount =
                        conflictCount(
                                result);

                assertThat(createdCount)
                        .as(
                                "same fingerprint Created count, attempt %s",
                                attempt)
                        .isEqualTo(
                                1);

                assertThat(replayCount)
                        .as(
                                "same fingerprint Replay count, attempt %s",
                                attempt)
                        .isEqualTo(
                                1);

                assertThat(conflictCount)
                        .as(
                                "same fingerprint conflict count, attempt %s",
                                attempt)
                        .isZero();

                var createdIntentId =
                        createdIntentId(
                                result);

                var replayIntentId =
                        replayIntentId(
                                result);

                assertThat(replayIntentId)
                        .as(
                                "Replay must reference durable winner, attempt %s",
                                attempt)
                        .isEqualTo(
                                createdIntentId);

                assertThat(
                        countByOperation(
                                fixture.tenantId(),
                                fixture.operationId()))
                        .as(
                                "same fingerprint row count, attempt %s",
                                attempt)
                        .isEqualTo(
                                1);

                var persisted =
                        loadByOperation(
                                fixture.tenantId(),
                                fixture.operationId());

                var winner =
                        winner(
                                firstIntent,
                                secondIntent,
                                createdIntentId);

                assertPersistedWinner(
                        persisted,
                        winner,
                        attempt,
                        "same fingerprint");
            }

        } finally {

            shutdown(
                    executor);
        }
    }

    @Test
    void concurrentSameOperationWithDifferentFingerprintsProduceOneCreatedAndOneConflict()
            throws Exception {

        var executor =
                Executors.newFixedThreadPool(
                        2);

        try {

            for (var attempt = 0;
                    attempt < RACE_ATTEMPTS;
                    attempt++) {

                var fixture =
                        fixture();

                var firstIntent =
                        intent(
                                fixture,
                                fixedUuid(
                                        0x41,
                                        attempt),
                                fixedBytes(
                                        0x41,
                                        attempt),
                                fixedBytes(
                                        0x51,
                                        attempt),
                                fixedUuid(
                                        0x42,
                                        attempt));

                var secondIntent =
                        intent(
                                fixture,
                                fixedUuid(
                                        0x61,
                                        attempt),
                                fixedBytes(
                                        0x61,
                                        attempt),
                                fixedBytes(
                                        0x71,
                                        attempt),
                                fixedUuid(
                                        0x62,
                                        attempt));

                var repository =
                        repository();

                var result =
                        race(
                                executor,
                                () -> repository.create(
                                        firstIntent),
                                () -> repository.create(
                                        secondIntent));

                var createdCount =
                        createdCount(
                                result);

                var replayCount =
                        replayCount(
                                result);

                var conflictCount =
                        conflictCount(
                                result);

                assertThat(createdCount)
                        .as(
                                "different fingerprint Created count, attempt %s",
                                attempt)
                        .isEqualTo(
                                1);

                assertThat(replayCount)
                        .as(
                                "different fingerprint Replay count, attempt %s",
                                attempt)
                        .isZero();

                assertThat(conflictCount)
                        .as(
                                "different fingerprint conflict count, attempt %s",
                                attempt)
                        .isEqualTo(
                                1);

                var createdIntentId =
                        createdIntentId(
                                result);

                assertThat(
                        countByOperation(
                                fixture.tenantId(),
                                fixture.operationId()))
                        .as(
                                "different fingerprint row count, attempt %s",
                                attempt)
                        .isEqualTo(
                                1);

                var persisted =
                        loadByOperation(
                                fixture.tenantId(),
                                fixture.operationId());

                var winner =
                        winner(
                                firstIntent,
                                secondIntent,
                                createdIntentId);

                assertPersistedWinner(
                        persisted,
                        winner,
                        attempt,
                        "different fingerprint");
            }

        } finally {

            shutdown(
                    executor);
        }
    }

    private StaffProvisioningIntentRepository repository() {

        return new PostgreSqlStaffProvisioningIntentRepository(
                jdbcTemplate);
    }

    private Fixture fixture() {

        var tenantId =
                UUID.randomUUID();

        var departmentId =
                UUID.randomUUID();

        var positionId =
                UUID.randomUUID();

        insertDepartment(
                departmentId,
                tenantId);

        insertPosition(
                positionId,
                tenantId);

        return new Fixture(
                tenantId,
                departmentId,
                positionId,
                UUID.randomUUID(),
                UUID.randomUUID(),
                OffsetDateTime.parse(
                        "2099-01-01T00:00:00Z"));
    }

    private NewStaffProvisioningIntent intent(
            Fixture fixture,
            UUID intentId,
            byte[] secretDigest,
            byte[] requestFingerprint,
            UUID correlationId) {

        return new NewStaffProvisioningIntent(
                intentId,
                fixture.tenantId(),
                secretDigest,
                fixture.issuedByUserId(),
                fixture.departmentId(),
                fixture.positionId(),
                "TENANT_STAFF",
                fixture.operationId(),
                requestFingerprint,
                fixture.expiresAt(),
                correlationId);
    }

    private int createdCount(
            RaceResult<StaffProvisioningIntentCreation,
                    StaffProvisioningIntentCreation> result) {

        return (result.first()
                        instanceof StaffProvisioningIntentCreation.Created
                ? 1
                : 0)
                + (result.second()
                                instanceof StaffProvisioningIntentCreation.Created
                        ? 1
                        : 0);
    }

    private int replayCount(
            RaceResult<StaffProvisioningIntentCreation,
                    StaffProvisioningIntentCreation> result) {

        return (result.first()
                        instanceof StaffProvisioningIntentCreation.Replay
                ? 1
                : 0)
                + (result.second()
                                instanceof StaffProvisioningIntentCreation.Replay
                        ? 1
                        : 0);
    }

    private int conflictCount(
            RaceResult<StaffProvisioningIntentCreation,
                    StaffProvisioningIntentCreation> result) {

        return (result.first()
                        instanceof StaffProvisioningIntentCreation.FingerprintConflict
                ? 1
                : 0)
                + (result.second()
                                instanceof StaffProvisioningIntentCreation.FingerprintConflict
                        ? 1
                        : 0);
    }

    private UUID createdIntentId(
            RaceResult<StaffProvisioningIntentCreation,
                    StaffProvisioningIntentCreation> result) {

        if (result.first()
                instanceof StaffProvisioningIntentCreation.Created created) {

            return created.intentId();
        }

        if (result.second()
                instanceof StaffProvisioningIntentCreation.Created created) {

            return created.intentId();
        }

        throw new AssertionError(
                "Race did not produce a Created outcome");
    }

    private UUID replayIntentId(
            RaceResult<StaffProvisioningIntentCreation,
                    StaffProvisioningIntentCreation> result) {

        if (result.first()
                instanceof StaffProvisioningIntentCreation.Replay replay) {

            return replay.intentId();
        }

        if (result.second()
                instanceof StaffProvisioningIntentCreation.Replay replay) {

            return replay.intentId();
        }

        throw new AssertionError(
                "Race did not produce a Replay outcome");
    }

    private NewStaffProvisioningIntent winner(
            NewStaffProvisioningIntent first,
            NewStaffProvisioningIntent second,
            UUID createdIntentId) {

        if (first.intentId()
                .equals(
                        createdIntentId)) {

            return first;
        }

        if (second.intentId()
                .equals(
                        createdIntentId)) {

            return second;
        }

        throw new AssertionError(
                "Created outcome does not match either concurrent input");
    }

    private void assertPersistedWinner(
            PersistedIntent persisted,
            NewStaffProvisioningIntent winner,
            int attempt,
            String scenario) {

        assertThat(persisted.intentId())
                .as(
                        "%s durable intent ID, attempt %s",
                        scenario,
                        attempt)
                .isEqualTo(
                        winner.intentId());

        assertThat(persisted.secretDigest())
                .as(
                        "%s durable credential digest, attempt %s",
                        scenario,
                        attempt)
                .containsExactly(
                        winner.secretDigest());

        assertThat(persisted.requestFingerprint())
                .as(
                        "%s durable request fingerprint, attempt %s",
                        scenario,
                        attempt)
                .containsExactly(
                        winner.requestFingerprint());

        assertThat(persisted.correlationId())
                .as(
                        "%s durable correlation, attempt %s",
                        scenario,
                        attempt)
                .isEqualTo(
                        winner.correlationId());

        assertThat(persisted.consumedAt())
                .as(
                        "%s must remain pending, attempt %s",
                        scenario,
                        attempt)
                .isNull();

        assertThat(persisted.cancelledAt())
                .as(
                        "%s must remain pending, attempt %s",
                        scenario,
                        attempt)
                .isNull();
    }

    private int countByOperation(
            UUID tenantId,
            UUID operationId) {

        var count =
                jdbcTemplate.queryForObject(
                        """
                        SELECT count(*)
                        FROM workforce.staff_provisioning_intents
                        WHERE tenant_id = ?
                          AND operation_id = ?
                        """,
                        Integer.class,
                        tenantId,
                        operationId);

        if (count == null) {
            throw new AssertionError(
                    "PostgreSQL did not return provisioning intent count");
        }

        return count;
    }

    private PersistedIntent loadByOperation(
            UUID tenantId,
            UUID operationId) {

        return jdbcTemplate.queryForObject(
                """
                SELECT
                    intent_id,
                    secret_digest,
                    request_fingerprint,
                    correlation_id,
                    consumed_at,
                    cancelled_at
                FROM workforce.staff_provisioning_intents
                WHERE tenant_id = ?
                  AND operation_id = ?
                """,
                (resultSet, rowNumber) ->
                        new PersistedIntent(
                                resultSet.getObject(
                                        "intent_id",
                                        UUID.class),
                                resultSet.getBytes(
                                        "secret_digest"),
                                resultSet.getBytes(
                                        "request_fingerprint"),
                                resultSet.getObject(
                                        "correlation_id",
                                        UUID.class),
                                resultSet.getObject(
                                        "consumed_at",
                                        OffsetDateTime.class),
                                resultSet.getObject(
                                        "cancelled_at",
                                        OffsetDateTime.class)),
                tenantId,
                operationId);
    }

    private <A, B> RaceResult<A, B> race(
            ExecutorService executor,
            Callable<A> firstActor,
            Callable<B> secondActor)
            throws Exception {

        var ready =
                new CountDownLatch(
                        2);

        var start =
                new CountDownLatch(
                        1);

        Future<A> first =
                executor.submit(
                        () -> {

                            ready.countDown();

                            if (!start.await(
                                    5,
                                    SECONDS)) {

                                throw new AssertionError(
                                        "First creation actor "
                                                + "did not receive start signal");
                            }

                            return firstActor.call();
                        });

        Future<B> second =
                executor.submit(
                        () -> {

                            ready.countDown();

                            if (!start.await(
                                    5,
                                    SECONDS)) {

                                throw new AssertionError(
                                        "Second creation actor "
                                                + "did not receive start signal");
                            }

                            return secondActor.call();
                        });

        if (!ready.await(
                5,
                SECONDS)) {

            first.cancel(
                    true);

            second.cancel(
                    true);

            throw new AssertionError(
                    "Both creation actors did not become ready");
        }

        start.countDown();

        return new RaceResult<>(
                first.get(
                        10,
                        SECONDS),
                second.get(
                        10,
                        SECONDS));
    }

    private void shutdown(
            ExecutorService executor)
            throws InterruptedException {

        executor.shutdownNow();

        assertThat(
                executor.awaitTermination(
                        5,
                        SECONDS))
                .as("creation concurrency executor must terminate")
                .isTrue();
    }

    private void insertDepartment(
            UUID departmentId,
            UUID tenantId) {

        jdbcTemplate.update(
                """
                INSERT INTO workforce.departments (
                    department_id,
                    tenant_id,
                    code,
                    name
                )
                VALUES (?, ?, ?, ?)
                """,
                departmentId,
                tenantId,
                "PROVISIONING",
                "Synthetic provisioning department");
    }

    private void insertPosition(
            UUID positionId,
            UUID tenantId) {

        jdbcTemplate.update(
                """
                INSERT INTO workforce.job_positions (
                    position_id,
                    tenant_id,
                    code,
                    title,
                    authority_band
                )
                VALUES (?, ?, ?, ?, ?)
                """,
                positionId,
                tenantId,
                "PROVISIONING_STAFF",
                "Synthetic provisioning Staff",
                "MANAGEMENT");
    }

    private byte[] fixedBytes(
            int family,
            int attempt) {

        var bytes =
                new byte[32];

        Arrays.fill(
                bytes,
                (byte) family);

        bytes[0] =
                (byte) family;

        bytes[1] =
                (byte) (attempt >>> 24);

        bytes[2] =
                (byte) (attempt >>> 16);

        bytes[3] =
                (byte) (attempt >>> 8);

        bytes[4] =
                (byte) attempt;

        return bytes;
    }

    private UUID fixedUuid(
            int family,
            int attempt) {

        var high =
                ((long) family << 32)
                        | (attempt & 0xffffffffL);

        var low =
                0x8000000000000000L
                        | ((long) family << 40)
                        | (attempt & 0xffffffffL);

        return new UUID(
                high,
                low);
    }

    private record Fixture(
            UUID tenantId,
            UUID departmentId,
            UUID positionId,
            UUID issuedByUserId,
            UUID operationId,
            OffsetDateTime expiresAt) {
    }

    private record PersistedIntent(
            UUID intentId,
            byte[] secretDigest,
            byte[] requestFingerprint,
            UUID correlationId,
            OffsetDateTime consumedAt,
            OffsetDateTime cancelledAt) {
    }

    private record RaceResult<A, B>(
            A first,
            B second) {
    }
}

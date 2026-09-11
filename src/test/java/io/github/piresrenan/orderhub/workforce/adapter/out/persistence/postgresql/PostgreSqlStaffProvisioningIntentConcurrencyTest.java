package io.github.piresrenan.orderhub.workforce.adapter.out.persistence.postgresql;

import static java.util.concurrent.TimeUnit.SECONDS;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.Optional;
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

import io.github.piresrenan.orderhub.workforce.application.model.ConsumedStaffProvisioningIntent;
import io.github.piresrenan.orderhub.workforce.application.port.out.StaffProvisioningIntentRepository;

/**
 * Why: Staff onboarding is a privileged one-time workflow rather than a caller-asserted identity.
 * Covers: The intent, issuance, consumption or composition boundary exercised by this suite.
 * Prevents: Replay corruption, authority bypass and incomplete atomic provisioning behavior.
 */
@Testcontainers
class PostgreSqlStaffProvisioningIntentConcurrencyTest {

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
    void concurrentConsumersProduceExactlyOneConsumptionWinner()
            throws Exception {

        var executor =
                Executors.newFixedThreadPool(
                        2);

        try {

            for (var attempt = 0;
                    attempt < RACE_ATTEMPTS;
                    attempt++) {

                var fixture =
                        insertPendingIntent(
                                0x10,
                                attempt);

                var terminalAt =
                        fixture.createdAt()
                                .plusHours(
                                        1);

                var repository =
                        repository();

                var result =
                        race(
                                executor,
                                () -> repository.consumePending(
                                        fixture.digest(),
                                        terminalAt),
                                () -> repository.consumePending(
                                        fixture.digest(),
                                        terminalAt));

                var winners =
                        (result.first().isPresent() ? 1 : 0)
                                + (result.second().isPresent() ? 1 : 0);

                assertThat(winners)
                        .as(
                                "consume x consume attempt %s",
                                attempt)
                        .isEqualTo(
                                1);

                var state =
                        loadState(
                                fixture.intentId());

                assertThat(state.consumedAt())
                        .as(
                                "consume x consume attempt %s",
                                attempt)
                        .isNotNull();

                assertThat(state.cancelledAt())
                        .as(
                                "consume x consume attempt %s",
                                attempt)
                        .isNull();

                assertThat(
                        state.consumedAt().toInstant())
                        .isEqualTo(
                                terminalAt.toInstant());
            }

        } finally {

            shutdown(
                    executor);
        }
    }

    @Test
    void concurrentCancellationsProduceExactlyOneCancellationWinner()
            throws Exception {

        var executor =
                Executors.newFixedThreadPool(
                        2);

        try {

            for (var attempt = 0;
                    attempt < RACE_ATTEMPTS;
                    attempt++) {

                var fixture =
                        insertPendingIntent(
                                0x30,
                                attempt);

                var terminalAt =
                        fixture.createdAt()
                                .plusHours(
                                        1);

                var repository =
                        repository();

                var result =
                        race(
                                executor,
                                () -> repository.cancelPending(
                                        fixture.tenantId(),
                                        fixture.intentId(),
                                        terminalAt),
                                () -> repository.cancelPending(
                                        fixture.tenantId(),
                                        fixture.intentId(),
                                        terminalAt));

                var winners =
                        (result.first() ? 1 : 0)
                                + (result.second() ? 1 : 0);

                assertThat(winners)
                        .as(
                                "cancel x cancel attempt %s",
                                attempt)
                        .isEqualTo(
                                1);

                var state =
                        loadState(
                                fixture.intentId());

                assertThat(state.consumedAt())
                        .as(
                                "cancel x cancel attempt %s",
                                attempt)
                        .isNull();

                assertThat(state.cancelledAt())
                        .as(
                                "cancel x cancel attempt %s",
                                attempt)
                        .isNotNull();

                assertThat(
                        state.cancelledAt().toInstant())
                        .isEqualTo(
                                terminalAt.toInstant());
            }

        } finally {

            shutdown(
                    executor);
        }
    }

    @Test
    void concurrentConsumptionAndCancellationProduceExactlyOneTerminalWinner()
            throws Exception {

        var executor =
                Executors.newFixedThreadPool(
                        2);

        try {

            for (var attempt = 0;
                    attempt < RACE_ATTEMPTS;
                    attempt++) {

                var fixture =
                        insertPendingIntent(
                                0x50,
                                attempt);

                var terminalAt =
                        fixture.createdAt()
                                .plusHours(
                                        1);

                var repository =
                        repository();

                var result =
                        race(
                                executor,
                                () -> repository.consumePending(
                                        fixture.digest(),
                                        terminalAt),
                                () -> repository.cancelPending(
                                        fixture.tenantId(),
                                        fixture.intentId(),
                                        terminalAt));

                var consumeWon =
                        result.first()
                                .isPresent();

                var cancelWon =
                        result.second();

                var winners =
                        (consumeWon ? 1 : 0)
                                + (cancelWon ? 1 : 0);

                assertThat(winners)
                        .as(
                                "consume x cancel attempt %s",
                                attempt)
                        .isEqualTo(
                                1);

                var state =
                        loadState(
                                fixture.intentId());

                if (consumeWon) {

                    assertThat(state.consumedAt())
                            .isNotNull();

                    assertThat(state.cancelledAt())
                            .isNull();

                    assertThat(
                            state.consumedAt().toInstant())
                            .isEqualTo(
                                    terminalAt.toInstant());

                } else {

                    assertThat(state.consumedAt())
                            .isNull();

                    assertThat(state.cancelledAt())
                            .isNotNull();

                    assertThat(
                            state.cancelledAt().toInstant())
                            .isEqualTo(
                                    terminalAt.toInstant());
                }
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
                                        "First concurrency actor "
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
                                        "Second concurrency actor "
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
                    "Both concurrency actors did not become ready");
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
                .as("concurrency executor must terminate")
                .isTrue();
    }

    private IntentFixture insertPendingIntent(
            int family,
            int attempt) {

        var intentId =
                UUID.randomUUID();

        var tenantId =
                UUID.randomUUID();

        var departmentId =
                UUID.randomUUID();

        var positionId =
                UUID.randomUUID();

        var digest =
                fixedBytes(
                        family,
                        attempt);

        var createdAt =
                OffsetDateTime.parse(
                        "2030-01-01T10:00:00Z");

        var expiresAt =
                createdAt.plusHours(
                        24);

        insertDepartment(
                departmentId,
                tenantId);

        insertPosition(
                positionId,
                tenantId);

        jdbcTemplate.update(
                """
                INSERT INTO workforce.staff_provisioning_intents (
                    intent_id,
                    tenant_id,
                    secret_digest,
                    issued_by_user_id,
                    department_id,
                    position_id,
                    initial_role_code,
                    operation_id,
                    request_fingerprint,
                    expires_at,
                    correlation_id,
                    created_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                intentId,
                tenantId,
                digest,
                UUID.randomUUID(),
                departmentId,
                positionId,
                "TENANT_STAFF",
                UUID.randomUUID(),
                fixedBytes(
                        family + 0x20,
                        attempt),
                expiresAt,
                UUID.randomUUID(),
                createdAt);

        return new IntentFixture(
                intentId,
                tenantId,
                digest,
                createdAt);
    }

    private TerminalState loadState(
            UUID intentId) {

        return jdbcTemplate.queryForObject(
                """
                SELECT
                    consumed_at,
                    cancelled_at
                FROM workforce.staff_provisioning_intents
                WHERE intent_id = ?
                """,
                (resultSet, rowNumber) ->
                        new TerminalState(
                                resultSet.getObject(
                                        "consumed_at",
                                        OffsetDateTime.class),
                                resultSet.getObject(
                                        "cancelled_at",
                                        OffsetDateTime.class)),
                intentId);
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
                "ONBOARDING",
                "Synthetic onboarding department");
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
                "ONBOARDING_STAFF",
                "Synthetic onboarding Staff",
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

    private record RaceResult<A, B>(
            A first,
            B second) {
    }

    private record IntentFixture(
            UUID intentId,
            UUID tenantId,
            byte[] digest,
            OffsetDateTime createdAt) {
    }

    private record TerminalState(
            OffsetDateTime consumedAt,
            OffsetDateTime cancelledAt) {
    }
}

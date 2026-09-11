package io.github.piresrenan.orderhub.workforce.adapter.out.persistence.postgresql;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.OffsetDateTime;
import java.util.Arrays;
import java.util.UUID;

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

import io.github.piresrenan.orderhub.workforce.application.port.out.StaffProvisioningIntentRepository;

/**
 * Why: Staff onboarding is a privileged one-time workflow rather than a caller-asserted identity.
 * Covers: The intent, issuance, consumption or composition boundary exercised by this suite.
 * Prevents: Replay corruption, authority bypass and incomplete atomic provisioning behavior.
 */
@Testcontainers
class PostgreSqlStaffProvisioningIntentCancellationTest {

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
    void pendingIntentCanBeCancelledByTenantAndDurableIntentIdentity() {

        var fixture =
                insertPendingIntent(
                        (byte) 0x61);

        var cancelledAt =
                fixture.createdAt()
                        .plusHours(
                                2);

        var cancelled =
                repository().cancelPending(
                        fixture.tenantId(),
                        fixture.intentId(),
                        cancelledAt);

        assertThat(cancelled)
                .isTrue();

        var state =
                loadState(
                        fixture.intentId());

        assertThat(state.consumedAt())
                .isNull();

        assertThat(state.cancelledAt())
                .isNotNull();

        assertThat(
                state.cancelledAt().toInstant())
                .isEqualTo(
                        cancelledAt.toInstant());
    }

    @Test
    void wrongTenantCannotCancelIntent() {

        var fixture =
                insertPendingIntent(
                        (byte) 0x62);

        var cancelled =
                repository().cancelPending(
                        UUID.randomUUID(),
                        fixture.intentId(),
                        fixture.createdAt()
                                .plusHours(
                                        1));

        assertThat(cancelled)
                .isFalse();

        assertStillPending(
                fixture.intentId());
    }

    @Test
    void unknownIntentCannotBeCancelled() {

        var fixture =
                insertPendingIntent(
                        (byte) 0x63);

        var cancelled =
                repository().cancelPending(
                        fixture.tenantId(),
                        UUID.randomUUID(),
                        fixture.createdAt()
                                .plusHours(
                                        1));

        assertThat(cancelled)
                .isFalse();

        assertStillPending(
                fixture.intentId());
    }

    @Test
    void consumedIntentCannotBeCancelled() {

        var fixture =
                insertPendingIntent(
                        (byte) 0x64);

        var consumedAt =
                fixture.createdAt()
                        .plusHours(
                                1);

        jdbcTemplate.update(
                """
                UPDATE workforce.staff_provisioning_intents
                SET consumed_at = ?
                WHERE intent_id = ?
                """,
                consumedAt,
                fixture.intentId());

        var cancelled =
                repository().cancelPending(
                        fixture.tenantId(),
                        fixture.intentId(),
                        consumedAt.plusHours(
                                1));

        assertThat(cancelled)
                .isFalse();

        var state =
                loadState(
                        fixture.intentId());

        assertThat(state.cancelledAt())
                .isNull();

        assertThat(state.consumedAt())
                .isNotNull();

        assertThat(
                state.consumedAt().toInstant())
                .isEqualTo(
                        consumedAt.toInstant());
    }

    @Test
    void alreadyCancelledIntentCannotRewriteOriginalTerminalInstant() {

        var fixture =
                insertPendingIntent(
                        (byte) 0x65);

        var firstCancellation =
                fixture.createdAt()
                        .plusHours(
                                1);

        jdbcTemplate.update(
                """
                UPDATE workforce.staff_provisioning_intents
                SET cancelled_at = ?
                WHERE intent_id = ?
                """,
                firstCancellation,
                fixture.intentId());

        var secondCancellation =
                firstCancellation.plusHours(
                        1);

        var cancelled =
                repository().cancelPending(
                        fixture.tenantId(),
                        fixture.intentId(),
                        secondCancellation);

        assertThat(cancelled)
                .isFalse();

        var state =
                loadState(
                        fixture.intentId());

        assertThat(state.consumedAt())
                .isNull();

        assertThat(state.cancelledAt())
                .isNotNull();

        assertThat(
                state.cancelledAt().toInstant())
                .isEqualTo(
                        firstCancellation.toInstant());
    }

    @Test
    void expiredPendingIntentCanStillBeExplicitlyCancelled() {

        var fixture =
                insertPendingIntent(
                        (byte) 0x66);

        var cancelledAt =
                fixture.expiresAt()
                        .plusHours(
                                1);

        var cancelled =
                repository().cancelPending(
                        fixture.tenantId(),
                        fixture.intentId(),
                        cancelledAt);

        assertThat(cancelled)
                .isTrue();

        var state =
                loadState(
                        fixture.intentId());

        assertThat(state.consumedAt())
                .isNull();

        assertThat(state.cancelledAt())
                .isNotNull();

        assertThat(
                state.cancelledAt().toInstant())
                .isEqualTo(
                        cancelledAt.toInstant());
    }

    @Test
    void cancellationTimestampBeforeCreationCannotMutateIntent() {

        var fixture =
                insertPendingIntent(
                        (byte) 0x67);

        var cancelled =
                repository().cancelPending(
                        fixture.tenantId(),
                        fixture.intentId(),
                        fixture.createdAt()
                                .minusSeconds(
                                        1));

        assertThat(cancelled)
                .isFalse();

        assertStillPending(
                fixture.intentId());
    }

    private StaffProvisioningIntentRepository repository() {

        return new PostgreSqlStaffProvisioningIntentRepository(
                jdbcTemplate);
    }

    private IntentFixture insertPendingIntent(
            byte digestSeed) {

        var intentId =
                UUID.randomUUID();

        var tenantId =
                UUID.randomUUID();

        var departmentId =
                UUID.randomUUID();

        var positionId =
                UUID.randomUUID();

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
                fixedBytes(
                        digestSeed),
                UUID.randomUUID(),
                departmentId,
                positionId,
                "TENANT_STAFF",
                UUID.randomUUID(),
                fixedBytes(
                        (byte) (digestSeed + 1)),
                expiresAt,
                UUID.randomUUID(),
                createdAt);

        return new IntentFixture(
                intentId,
                tenantId,
                createdAt,
                expiresAt);
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

    private void assertStillPending(
            UUID intentId) {

        var state =
                loadState(
                        intentId);

        assertThat(state.consumedAt())
                .isNull();

        assertThat(state.cancelledAt())
                .isNull();
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
            byte value) {

        var bytes =
                new byte[32];

        Arrays.fill(
                bytes,
                value);

        return bytes;
    }

    private record IntentFixture(
            UUID intentId,
            UUID tenantId,
            OffsetDateTime createdAt,
            OffsetDateTime expiresAt) {
    }

    private record TerminalState(
            OffsetDateTime consumedAt,
            OffsetDateTime cancelledAt) {
    }
}

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

@Testcontainers
class PostgreSqlStaffProvisioningIntentConsumptionStateTest {

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
    void unknownDigestDoesNotConsumeAnotherPendingIntent() {

        var fixture =
                insertPendingIntent(
                        (byte) 0x11);

        var repository =
                new PostgreSqlStaffProvisioningIntentRepository(
                        jdbcTemplate);

        var result =
                repository.consumePending(
                        fixedBytes(
                                (byte) 0x22),
                        fixture.createdAt()
                                .plusHours(
                                        1));

        assertThat(result)
                .isEmpty();

        assertStillPending(
                fixture.intentId());
    }

    @Test
    void cancelledIntentCannotBeConsumedAndCancellationInstantIsPreserved() {

        var fixture =
                insertPendingIntent(
                        (byte) 0x31);

        var cancelledAt =
                fixture.createdAt()
                        .plusHours(
                                1);

        jdbcTemplate.update(
                """
                UPDATE workforce.staff_provisioning_intents
                SET cancelled_at = ?
                WHERE intent_id = ?
                """,
                cancelledAt,
                fixture.intentId());

        var repository =
                new PostgreSqlStaffProvisioningIntentRepository(
                        jdbcTemplate);

        var result =
                repository.consumePending(
                        fixture.digest(),
                        cancelledAt.plusMinutes(
                                30));

        assertThat(result)
                .isEmpty();

        var row =
                loadTerminalState(
                        fixture.intentId());

        assertThat(row.consumedAt())
                .isNull();

        assertThat(row.cancelledAt())
                .isNotNull();

        assertThat(
                row.cancelledAt().toInstant())
                .isEqualTo(
                        cancelledAt.toInstant());
    }

    @Test
    void expiryBoundaryIsInclusiveAndDoesNotMutateIntent() {

        var fixture =
                insertPendingIntent(
                        (byte) 0x41);

        var repository =
                new PostgreSqlStaffProvisioningIntentRepository(
                        jdbcTemplate);

        var result =
                repository.consumePending(
                        fixture.digest(),
                        fixture.expiresAt());

        assertThat(result)
                .as("expires_at <= evaluation time is already expired")
                .isEmpty();

        assertStillPending(
                fixture.intentId());
    }

    @Test
    void consumptionTimestampBeforeCreationCannotMutateIntent() {

        var fixture =
                insertPendingIntent(
                        (byte) 0x51);

        var repository =
                new PostgreSqlStaffProvisioningIntentRepository(
                        jdbcTemplate);

        var result =
                repository.consumePending(
                        fixture.digest(),
                        fixture.createdAt()
                                .minusSeconds(
                                        1));

        assertThat(result)
                .as("a terminal instant cannot precede durable creation")
                .isEmpty();

        assertStillPending(
                fixture.intentId());
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

        var digest =
                fixedBytes(
                        digestSeed);

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
                        (byte) (digestSeed + 1)),
                expiresAt,
                UUID.randomUUID(),
                createdAt);

        return new IntentFixture(
                intentId,
                digest,
                createdAt,
                expiresAt);
    }

    private void assertStillPending(
            UUID intentId) {

        var row =
                loadTerminalState(
                        intentId);

        assertThat(row.consumedAt())
                .isNull();

        assertThat(row.cancelledAt())
                .isNull();
    }

    private TerminalState loadTerminalState(
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
            byte[] digest,
            OffsetDateTime createdAt,
            OffsetDateTime expiresAt) {
    }

    private record TerminalState(
            OffsetDateTime consumedAt,
            OffsetDateTime cancelledAt) {
    }
}

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

@Testcontainers
class PostgreSqlStaffProvisioningIntentConsumptionTest {

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
    void pendingIntentCanBeConsumedExactlyOnceAndReturnsFrozenRelationship() {

        var intentId =
                UUID.randomUUID();

        var tenantId =
                UUID.randomUUID();

        var issuingUserId =
                UUID.randomUUID();

        var departmentId =
                UUID.randomUUID();

        var positionId =
                UUID.randomUUID();

        var operationId =
                UUID.randomUUID();

        var correlationId =
                UUID.randomUUID();

        var digest =
                fixedBytes(
                        (byte) 0x31);

        var fingerprint =
                fixedBytes(
                        (byte) 0x52);

        var createdAt =
                OffsetDateTime.parse(
                        "2030-01-01T10:00:00Z");

        var expiresAt =
                OffsetDateTime.parse(
                        "2030-01-02T10:00:00Z");

        var consumedAt =
                OffsetDateTime.parse(
                        "2030-01-01T12:00:00Z");

        insertDepartment(
                departmentId,
                tenantId);

        insertPosition(
                positionId,
                tenantId);

        insertIntent(
                intentId,
                tenantId,
                digest,
                issuingUserId,
                departmentId,
                positionId,
                operationId,
                fingerprint,
                expiresAt,
                correlationId,
                createdAt);

        var repository =
                repository();

        var first =
                repository.consumePending(
                        digest,
                        consumedAt);

        assertThat(first)
                .isPresent();

        var consumed =
                first.orElseThrow();

        assertThat(consumed.intentId())
                .isEqualTo(
                        intentId);

        assertThat(consumed.tenantId())
                .isEqualTo(
                        tenantId);

        assertThat(consumed.issuedByUserId())
                .isEqualTo(
                        issuingUserId);

        assertThat(consumed.departmentId())
                .isEqualTo(
                        departmentId);

        assertThat(consumed.positionId())
                .isEqualTo(
                        positionId);

        assertThat(consumed.initialRoleCode())
                .isEqualTo(
                        "TENANT_STAFF");

        assertThat(consumed.correlationId())
                .isEqualTo(
                        correlationId);

        var persistedConsumedAt =
                jdbcTemplate.queryForObject(
                        """
                        SELECT consumed_at
                        FROM workforce.staff_provisioning_intents
                        WHERE intent_id = ?
                        """,
                        OffsetDateTime.class,
                        intentId);

        assertThat(persistedConsumedAt)
                .isNotNull();

        assertThat(
                persistedConsumedAt.toInstant())
                .isEqualTo(
                        consumedAt.toInstant());

        var replay =
                repository.consumePending(
                        digest,
                        consumedAt.plusMinutes(
                                5));

        assertThat(replay)
                .as("a consumed provisioning credential must be single-use")
                .isEmpty();

        var consumedAtAfterReplay =
                jdbcTemplate.queryForObject(
                        """
                        SELECT consumed_at
                        FROM workforce.staff_provisioning_intents
                        WHERE intent_id = ?
                        """,
                        OffsetDateTime.class,
                        intentId);

        assertThat(consumedAtAfterReplay)
                .isNotNull();

        assertThat(
                consumedAtAfterReplay.toInstant())
                .as("replay must not rewrite the original terminal instant")
                .isEqualTo(
                        consumedAt.toInstant());
    }

    private StaffProvisioningIntentRepository repository() {

        var implementationClass =
                "io.github.piresrenan.orderhub.workforce"
                        + ".adapter.out.persistence.postgresql"
                        + ".PostgreSqlStaffProvisioningIntentRepository";

        try {

            var type =
                    Class.forName(
                            implementationClass);

            var constructor =
                    type.getConstructor(
                            JdbcTemplate.class);

            var instance =
                    constructor.newInstance(
                            jdbcTemplate);

            if (!(instance
                    instanceof StaffProvisioningIntentRepository repository)) {

                throw new AssertionError(
                        implementationClass
                                + " must implement "
                                + StaffProvisioningIntentRepository.class
                                        .getName());
            }

            return repository;

        } catch (ClassNotFoundException exception) {

            throw new AssertionError(
                    "PostgreSQL Staff provisioning intent persistence "
                            + "adapter is missing",
                    exception);

        } catch (ReflectiveOperationException exception) {

            throw new AssertionError(
                    "PostgreSQL Staff provisioning intent persistence "
                            + "adapter cannot be constructed",
                    exception);
        }
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

    private void insertIntent(
            UUID intentId,
            UUID tenantId,
            byte[] secretDigest,
            UUID issuedByUserId,
            UUID departmentId,
            UUID positionId,
            UUID operationId,
            byte[] requestFingerprint,
            OffsetDateTime expiresAt,
            UUID correlationId,
            OffsetDateTime createdAt) {

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
                secretDigest,
                issuedByUserId,
                departmentId,
                positionId,
                "TENANT_STAFF",
                operationId,
                requestFingerprint,
                expiresAt,
                correlationId,
                createdAt);
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
}

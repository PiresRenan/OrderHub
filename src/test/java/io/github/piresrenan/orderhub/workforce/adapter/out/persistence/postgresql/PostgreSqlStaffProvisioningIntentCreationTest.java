package io.github.piresrenan.orderhub.workforce.adapter.out.persistence.postgresql;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.InvocationTargetException;
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

import io.github.piresrenan.orderhub.workforce.application.model.NewStaffProvisioningIntent;
import io.github.piresrenan.orderhub.workforce.application.model.StaffProvisioningIntentCreation;

/**
 * Why: Staff onboarding is a privileged one-time workflow rather than a caller-asserted identity.
 * Covers: The intent, issuance, consumption or composition boundary exercised by this suite.
 * Prevents: Replay corruption, authority bypass and incomplete atomic provisioning behavior.
 */
@Testcontainers
class PostgreSqlStaffProvisioningIntentCreationTest {

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
    void completePendingIntentCanBeEstablishedThroughPersistenceBoundary() {

        var intentId =
                UUID.fromString(
                        "10000000-0000-4000-8000-000000000001");

        var tenantId =
                UUID.fromString(
                        "10000000-0000-4000-8000-000000000002");

        var issuedByUserId =
                UUID.fromString(
                        "10000000-0000-4000-8000-000000000003");

        var departmentId =
                UUID.fromString(
                        "10000000-0000-4000-8000-000000000004");

        var positionId =
                UUID.fromString(
                        "10000000-0000-4000-8000-000000000005");

        var operationId =
                UUID.fromString(
                        "10000000-0000-4000-8000-000000000006");

        var correlationId =
                UUID.fromString(
                        "10000000-0000-4000-8000-000000000007");

        var secretDigest =
                fixedBytes(
                        (byte) 0x21);

        var requestFingerprint =
                fixedBytes(
                        (byte) 0x31);

        var expiresAt =
                OffsetDateTime.parse(
                        "2099-01-01T00:00:00Z");

        insertDepartment(
                departmentId,
                tenantId);

        insertPosition(
                positionId,
                tenantId);

        var intent =
                new NewStaffProvisioningIntent(
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
                        correlationId);

        var repository =
                new PostgreSqlStaffProvisioningIntentRepository(
                        jdbcTemplate);

        var outcome =
                invokeCreation(
                        repository,
                        intent);

        assertThat(outcome)
                .isInstanceOf(
                        StaffProvisioningIntentCreation.Created.class);

        var created =
                (StaffProvisioningIntentCreation.Created) outcome;

        assertThat(created.intentId())
                .isEqualTo(
                        intentId);

        var persisted =
                loadIntent(
                        intentId);

        assertThat(persisted.tenantId())
                .isEqualTo(
                        tenantId);

        assertThat(persisted.issuedByUserId())
                .isEqualTo(
                        issuedByUserId);

        assertThat(persisted.departmentId())
                .isEqualTo(
                        departmentId);

        assertThat(persisted.positionId())
                .isEqualTo(
                        positionId);

        assertThat(persisted.initialRoleCode())
                .isEqualTo(
                        "TENANT_STAFF");

        assertThat(persisted.operationId())
                .isEqualTo(
                        operationId);

        assertThat(persisted.correlationId())
                .isEqualTo(
                        correlationId);

        assertThat(persisted.secretDigest())
                .containsExactly(
                        secretDigest);

        assertThat(persisted.requestFingerprint())
                .containsExactly(
                        requestFingerprint);

        assertThat(
                persisted.expiresAt().toInstant())
                .isEqualTo(
                        expiresAt.toInstant());

        assertThat(persisted.createdAt())
                .isNotNull();

        assertThat(persisted.consumedAt())
                .isNull();

        assertThat(persisted.cancelledAt())
                .isNull();
    }

    private StaffProvisioningIntentCreation invokeCreation(
            PostgreSqlStaffProvisioningIntentRepository repository,
            NewStaffProvisioningIntent intent) {

        try {

            var method =
                    PostgreSqlStaffProvisioningIntentRepository.class
                            .getMethod(
                                    "create",
                                    NewStaffProvisioningIntent.class);

            var outcome =
                    method.invoke(
                            repository,
                            intent);

            if (!(outcome instanceof StaffProvisioningIntentCreation creation)) {

                throw new AssertionError(
                        "create must return StaffProvisioningIntentCreation");
            }

            return creation;

        } catch (NoSuchMethodException exception) {

            throw new AssertionError(
                    "PostgreSQL Staff provisioning intent creation "
                            + "operation is missing",
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
                    "PostgreSQL Staff provisioning intent creation "
                            + "operation failed unexpectedly",
                    cause);

        } catch (IllegalAccessException exception) {

            throw new AssertionError(
                    "PostgreSQL Staff provisioning intent creation "
                            + "operation cannot be accessed",
                    exception);
        }
    }

    private PersistedIntent loadIntent(
            UUID intentId) {

        return jdbcTemplate.queryForObject(
                """
                SELECT
                    tenant_id,
                    secret_digest,
                    issued_by_user_id,
                    department_id,
                    position_id,
                    initial_role_code,
                    operation_id,
                    request_fingerprint,
                    expires_at,
                    consumed_at,
                    cancelled_at,
                    created_at,
                    correlation_id
                FROM workforce.staff_provisioning_intents
                WHERE intent_id = ?
                """,
                (resultSet, rowNumber) ->
                        new PersistedIntent(
                                resultSet.getObject(
                                        "tenant_id",
                                        UUID.class),
                                resultSet.getBytes(
                                        "secret_digest"),
                                resultSet.getObject(
                                        "issued_by_user_id",
                                        UUID.class),
                                resultSet.getObject(
                                        "department_id",
                                        UUID.class),
                                resultSet.getObject(
                                        "position_id",
                                        UUID.class),
                                resultSet.getString(
                                        "initial_role_code"),
                                resultSet.getObject(
                                        "operation_id",
                                        UUID.class),
                                resultSet.getBytes(
                                        "request_fingerprint"),
                                resultSet.getObject(
                                        "expires_at",
                                        OffsetDateTime.class),
                                resultSet.getObject(
                                        "consumed_at",
                                        OffsetDateTime.class),
                                resultSet.getObject(
                                        "cancelled_at",
                                        OffsetDateTime.class),
                                resultSet.getObject(
                                        "created_at",
                                        OffsetDateTime.class),
                                resultSet.getObject(
                                        "correlation_id",
                                        UUID.class)),
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
            byte value) {

        var bytes =
                new byte[32];

        Arrays.fill(
                bytes,
                value);

        return bytes;
    }

    private record PersistedIntent(
            UUID tenantId,
            byte[] secretDigest,
            UUID issuedByUserId,
            UUID departmentId,
            UUID positionId,
            String initialRoleCode,
            UUID operationId,
            byte[] requestFingerprint,
            OffsetDateTime expiresAt,
            OffsetDateTime consumedAt,
            OffsetDateTime cancelledAt,
            OffsetDateTime createdAt,
            UUID correlationId) {
    }
}

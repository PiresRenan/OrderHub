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

import io.github.piresrenan.orderhub.workforce.application.model.NewStaffProvisioningIntent;
import io.github.piresrenan.orderhub.workforce.application.model.StaffProvisioningIntentCreation;
import io.github.piresrenan.orderhub.workforce.application.port.out.StaffProvisioningIntentPersistenceException;
import io.github.piresrenan.orderhub.workforce.application.port.out.StaffProvisioningIntentRepository;

@Testcontainers
class PostgreSqlStaffProvisioningIntentCreationRetryTest {

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
    void sameOperationAndFingerprintReplaysOriginalIntentWithoutMintingAnotherCredential() {

        var fixture =
                fixture();

        var repository =
                repository();

        var original =
                new NewStaffProvisioningIntent(
                        fixture.originalIntentId(),
                        fixture.tenantId(),
                        fixedBytes(
                                (byte) 0x11),
                        fixture.issuedByUserId(),
                        fixture.departmentId(),
                        fixture.positionId(),
                        "TENANT_STAFF",
                        fixture.operationId(),
                        fixedBytes(
                                (byte) 0x21),
                        fixture.expiresAt(),
                        fixture.originalCorrelationId());

        var first =
                repository.create(
                        original);

        assertThat(first)
                .isEqualTo(
                        new StaffProvisioningIntentCreation.Created(
                                fixture.originalIntentId()));

        /*
         * Simulates a lost first response.
         *
         * A caller may have generated fresh ephemeral values before discovering
         * this is a retry. Persistence must never replace the already-issued
         * credential or durable intent identity.
         */
        var retry =
                new NewStaffProvisioningIntent(
                        UUID.fromString(
                                "20000000-0000-4000-8000-000000000101"),
                        fixture.tenantId(),
                        fixedBytes(
                                (byte) 0x12),
                        fixture.issuedByUserId(),
                        fixture.departmentId(),
                        fixture.positionId(),
                        "TENANT_STAFF",
                        fixture.operationId(),
                        fixedBytes(
                                (byte) 0x21),
                        fixture.expiresAt(),
                        UUID.fromString(
                                "20000000-0000-4000-8000-000000000102"));

        var replay =
                createRetry(
                        repository,
                        retry);

        assertThat(replay)
                .isEqualTo(
                        new StaffProvisioningIntentCreation.Replay(
                                fixture.originalIntentId()));

        assertThat(
                countByOperation(
                        fixture.tenantId(),
                        fixture.operationId()))
                .isEqualTo(
                        1);

        var persisted =
                loadByOperation(
                        fixture.tenantId(),
                        fixture.operationId());

        assertThat(persisted.intentId())
                .isEqualTo(
                        fixture.originalIntentId());

        assertThat(persisted.secretDigest())
                .containsExactly(
                        original.secretDigest());

        assertThat(persisted.requestFingerprint())
                .containsExactly(
                        original.requestFingerprint());

        assertThat(persisted.correlationId())
                .isEqualTo(
                        fixture.originalCorrelationId());
    }

    @Test
    void sameOperationWithDifferentFingerprintReturnsConflictWithoutMutatingOriginalIntent() {

        var fixture =
                fixture();

        var repository =
                repository();

        var original =
                new NewStaffProvisioningIntent(
                        fixture.originalIntentId(),
                        fixture.tenantId(),
                        fixedBytes(
                                (byte) 0x31),
                        fixture.issuedByUserId(),
                        fixture.departmentId(),
                        fixture.positionId(),
                        "TENANT_STAFF",
                        fixture.operationId(),
                        fixedBytes(
                                (byte) 0x41),
                        fixture.expiresAt(),
                        fixture.originalCorrelationId());

        var first =
                repository.create(
                        original);

        assertThat(first)
                .isEqualTo(
                        new StaffProvisioningIntentCreation.Created(
                                fixture.originalIntentId()));

        var conflicting =
                new NewStaffProvisioningIntent(
                        UUID.fromString(
                                "20000000-0000-4000-8000-000000000201"),
                        fixture.tenantId(),
                        fixedBytes(
                                (byte) 0x32),
                        fixture.issuedByUserId(),
                        fixture.departmentId(),
                        fixture.positionId(),
                        "TENANT_MANAGER",
                        fixture.operationId(),
                        fixedBytes(
                                (byte) 0x42),
                        fixture.expiresAt(),
                        UUID.fromString(
                                "20000000-0000-4000-8000-000000000202"));

        var conflict =
                createRetry(
                        repository,
                        conflicting);

        assertThat(conflict)
                .isInstanceOf(
                        StaffProvisioningIntentCreation.FingerprintConflict.class);

        assertThat(
                countByOperation(
                        fixture.tenantId(),
                        fixture.operationId()))
                .isEqualTo(
                        1);

        var persisted =
                loadByOperation(
                        fixture.tenantId(),
                        fixture.operationId());

        assertThat(persisted.intentId())
                .isEqualTo(
                        fixture.originalIntentId());

        assertThat(persisted.secretDigest())
                .containsExactly(
                        original.secretDigest());

        assertThat(persisted.requestFingerprint())
                .containsExactly(
                        original.requestFingerprint());

        assertThat(persisted.initialRoleCode())
                .isEqualTo(
                        "TENANT_STAFF");

        assertThat(persisted.correlationId())
                .isEqualTo(
                        fixture.originalCorrelationId());
    }

    private StaffProvisioningIntentCreation createRetry(
            StaffProvisioningIntentRepository repository,
            NewStaffProvisioningIntent intent) {

        try {

            return repository.create(
                    intent);

        } catch (StaffProvisioningIntentPersistenceException exception) {

            throw new AssertionError(
                    "Staff provisioning creation retry semantics are missing",
                    exception);
        }
    }

    private StaffProvisioningIntentRepository repository() {

        return new PostgreSqlStaffProvisioningIntentRepository(
                jdbcTemplate);
    }

    private Fixture fixture() {

        var tenantId =
                UUID.fromString(
                        "20000000-0000-4000-8000-000000000001");

        var departmentId =
                UUID.fromString(
                        "20000000-0000-4000-8000-000000000002");

        var positionId =
                UUID.fromString(
                        "20000000-0000-4000-8000-000000000003");

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
                UUID.fromString(
                        "20000000-0000-4000-8000-000000000004"),
                UUID.fromString(
                        "20000000-0000-4000-8000-000000000005"),
                UUID.fromString(
                        "20000000-0000-4000-8000-000000000006"),
                UUID.fromString(
                        "20000000-0000-4000-8000-000000000007"),
                OffsetDateTime.parse(
                        "2099-01-01T00:00:00Z"));
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
                    initial_role_code,
                    correlation_id
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
                                resultSet.getString(
                                        "initial_role_code"),
                                resultSet.getObject(
                                        "correlation_id",
                                        UUID.class)),
                tenantId,
                operationId);
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

    private record Fixture(
            UUID tenantId,
            UUID departmentId,
            UUID positionId,
            UUID issuedByUserId,
            UUID operationId,
            UUID originalIntentId,
            UUID originalCorrelationId,
            OffsetDateTime expiresAt) {
    }

    private record PersistedIntent(
            UUID intentId,
            byte[] secretDigest,
            byte[] requestFingerprint,
            String initialRoleCode,
            UUID correlationId) {
    }
}

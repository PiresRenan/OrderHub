package io.github.piresrenan.orderhub.orders.adapter.out.persistence.postgresql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Adversarial verification of the relational invariants protecting durable
 * create-Order idempotency records.
 *
 * <p>
 * These tests deliberately bypass all future repository and application
 * behavior so PostgreSQL itself must reject structurally invalid durable
 * states.
 * </p>
 */
@Testcontainers
class PostgreSqlOrderIdempotencyConstraintsTest {

    private static final DockerImageName POSTGRES_IMAGE =
            DockerImageName.parse(
                    "postgres:18.6-trixie@sha256:"
                            + "4ef4dbc939d61acea57712655ddb4b4ab27419c913f94cca0cd57cb3ea3c2280")
                    .asCompatibleSubstituteFor(
                            "postgres");

    private static final UUID TENANT_A =
            UUID.fromString(
                    "11111111-1111-1111-1111-111111111111");

    private static final UUID TENANT_B =
            UUID.fromString(
                    "22222222-2222-2222-2222-222222222222");

    private static final UUID ORDER_ID =
            UUID.fromString(
                    "33333333-3333-3333-3333-333333333333");

    private static final String OPERATION =
            "CREATE_ORDER_V1";

    private static final Timestamp COMPLETED_AT =
            Timestamp.from(
                    Instant.parse(
                            "2026-09-02T10:00:00Z"));

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
    void cleanIdempotencyState() {

        jdbcTemplate.update(
                """
                TRUNCATE TABLE
                    orders.order_request_idempotency
                """);
    }

    @Test
    void acceptsValidProcessingShape() {

        insertRecord(
                TENANT_A,
                OPERATION,
                digest(
                        32,
                        (byte) 1),
                digest(
                        32,
                        (byte) 11),
                "PROCESSING",
                null,
                null,
                null,
                null);

        assertThat(rowCount())
                .isEqualTo(
                        1);
    }

    @Test
    void acceptsValidCompletedShape() {

        insertRecord(
                TENANT_A,
                OPERATION,
                digest(
                        32,
                        (byte) 2),
                digest(
                        32,
                        (byte) 12),
                "COMPLETED",
                ORDER_ID,
                "CREATED",
                "FULLY_ALLOCATED",
                COMPLETED_AT);

        assertThat(rowCount())
                .isEqualTo(
                        1);
    }

    @Test
    void allowsSameKeyIdentityAcrossDifferentTenants() {

        var keyDigest =
                digest(
                        32,
                        (byte) 3);

        insertRecord(
                TENANT_A,
                OPERATION,
                keyDigest,
                digest(
                        32,
                        (byte) 13),
                "PROCESSING",
                null,
                null,
                null,
                null);

        insertRecord(
                TENANT_B,
                OPERATION,
                keyDigest,
                digest(
                        32,
                        (byte) 14),
                "PROCESSING",
                null,
                null,
                null,
                null);

        assertThat(rowCount())
                .isEqualTo(
                        2);
    }

    @Test
    void rejectsDuplicateDurableIdentityWithinSameTenantAndOperation() {

        var keyDigest =
                digest(
                        32,
                        (byte) 4);

        insertRecord(
                TENANT_A,
                OPERATION,
                keyDigest,
                digest(
                        32,
                        (byte) 15),
                "PROCESSING",
                null,
                null,
                null,
                null);

        assertThatThrownBy(() ->
                insertRecord(
                        TENANT_A,
                        OPERATION,
                        keyDigest,
                        digest(
                                32,
                                (byte) 16),
                        "PROCESSING",
                        null,
                        null,
                        null,
                        null))
                .isInstanceOf(
                        DataIntegrityViolationException.class);
    }

    @Test
    void rejectsKeyDigestShorterThanSha256() {

        assertThatThrownBy(() ->
                insertProcessing(
                        TENANT_A,
                        digest(
                                31,
                                (byte) 5),
                        digest(
                                32,
                                (byte) 17)))
                .isInstanceOf(
                        DataIntegrityViolationException.class);
    }

    @Test
    void rejectsKeyDigestLongerThanSha256() {

        assertThatThrownBy(() ->
                insertProcessing(
                        TENANT_A,
                        digest(
                                33,
                                (byte) 6),
                        digest(
                                32,
                                (byte) 18)))
                .isInstanceOf(
                        DataIntegrityViolationException.class);
    }

    @Test
    void rejectsFingerprintShorterThanSha256() {

        assertThatThrownBy(() ->
                insertProcessing(
                        TENANT_A,
                        digest(
                                32,
                                (byte) 7),
                        digest(
                                31,
                                (byte) 19)))
                .isInstanceOf(
                        DataIntegrityViolationException.class);
    }

    @Test
    void rejectsFingerprintLongerThanSha256() {

        assertThatThrownBy(() ->
                insertProcessing(
                        TENANT_A,
                        digest(
                                32,
                                (byte) 8),
                        digest(
                                33,
                                (byte) 20)))
                .isInstanceOf(
                        DataIntegrityViolationException.class);
    }

    @Test
    void rejectsUnsupportedOperation() {

        assertThatThrownBy(() ->
                insertRecord(
                        TENANT_A,
                        "CREATE_ORDER_V2",
                        digest(
                                32,
                                (byte) 9),
                        digest(
                                32,
                                (byte) 21),
                        "PROCESSING",
                        null,
                        null,
                        null,
                        null))
                .isInstanceOf(
                        DataIntegrityViolationException.class);
    }

    @Test
    void rejectsUnsupportedState() {

        assertThatThrownBy(() ->
                insertRecord(
                        TENANT_A,
                        OPERATION,
                        digest(
                                32,
                                (byte) 10),
                        digest(
                                32,
                                (byte) 22),
                        "PENDING",
                        null,
                        null,
                        null,
                        null))
                .isInstanceOf(
                        DataIntegrityViolationException.class);
    }

    @Test
    void rejectsProcessingRowsWithCompletionProjection() {

        assertThatThrownBy(() ->
                insertRecord(
                        TENANT_A,
                        OPERATION,
                        digest(
                                32,
                                (byte) 31),
                        digest(
                                32,
                                (byte) 41),
                        "PROCESSING",
                        ORDER_ID,
                        null,
                        null,
                        null))
                .isInstanceOf(
                        DataIntegrityViolationException.class);

        assertThatThrownBy(() ->
                insertRecord(
                        TENANT_A,
                        OPERATION,
                        digest(
                                32,
                                (byte) 32),
                        digest(
                                32,
                                (byte) 42),
                        "PROCESSING",
                        null,
                        "CREATED",
                        null,
                        null))
                .isInstanceOf(
                        DataIntegrityViolationException.class);

        assertThatThrownBy(() ->
                insertRecord(
                        TENANT_A,
                        OPERATION,
                        digest(
                                32,
                                (byte) 33),
                        digest(
                                32,
                                (byte) 43),
                        "PROCESSING",
                        null,
                        null,
                        "FULLY_ALLOCATED",
                        null))
                .isInstanceOf(
                        DataIntegrityViolationException.class);

        assertThatThrownBy(() ->
                insertRecord(
                        TENANT_A,
                        OPERATION,
                        digest(
                                32,
                                (byte) 34),
                        digest(
                                32,
                                (byte) 44),
                        "PROCESSING",
                        null,
                        null,
                        null,
                        COMPLETED_AT))
                .isInstanceOf(
                        DataIntegrityViolationException.class);
    }

    @Test
    void rejectsIncompleteCompletedProjection() {

        assertThatThrownBy(() ->
                insertRecord(
                        TENANT_A,
                        OPERATION,
                        digest(
                                32,
                                (byte) 51),
                        digest(
                                32,
                                (byte) 61),
                        "COMPLETED",
                        null,
                        "CREATED",
                        "FULLY_ALLOCATED",
                        COMPLETED_AT))
                .isInstanceOf(
                        DataIntegrityViolationException.class);

        assertThatThrownBy(() ->
                insertRecord(
                        TENANT_A,
                        OPERATION,
                        digest(
                                32,
                                (byte) 52),
                        digest(
                                32,
                                (byte) 62),
                        "COMPLETED",
                        ORDER_ID,
                        null,
                        "FULLY_ALLOCATED",
                        COMPLETED_AT))
                .isInstanceOf(
                        DataIntegrityViolationException.class);

        assertThatThrownBy(() ->
                insertRecord(
                        TENANT_A,
                        OPERATION,
                        digest(
                                32,
                                (byte) 53),
                        digest(
                                32,
                                (byte) 63),
                        "COMPLETED",
                        ORDER_ID,
                        "CREATED",
                        null,
                        COMPLETED_AT))
                .isInstanceOf(
                        DataIntegrityViolationException.class);

        assertThatThrownBy(() ->
                insertRecord(
                        TENANT_A,
                        OPERATION,
                        digest(
                                32,
                                (byte) 54),
                        digest(
                                32,
                                (byte) 64),
                        "COMPLETED",
                        ORDER_ID,
                        "CREATED",
                        "FULLY_ALLOCATED",
                        null))
                .isInstanceOf(
                        DataIntegrityViolationException.class);
    }

    @Test
    void rejectsUnsupportedCompletedOrderStatus() {

        assertThatThrownBy(() ->
                insertRecord(
                        TENANT_A,
                        OPERATION,
                        digest(
                                32,
                                (byte) 71),
                        digest(
                                32,
                                (byte) 81),
                        "COMPLETED",
                        ORDER_ID,
                        "PAID",
                        "FULLY_ALLOCATED",
                        COMPLETED_AT))
                .isInstanceOf(
                        DataIntegrityViolationException.class);
    }

    @Test
    void rejectsUnsupportedCompletedAllocationOutcome() {

        assertThatThrownBy(() ->
                insertRecord(
                        TENANT_A,
                        OPERATION,
                        digest(
                                32,
                                (byte) 72),
                        digest(
                                32,
                                (byte) 82),
                        "COMPLETED",
                        ORDER_ID,
                        "CREATED",
                        "UNKNOWN",
                        COMPLETED_AT))
                .isInstanceOf(
                        DataIntegrityViolationException.class);
    }

    private void insertProcessing(
            UUID tenantId,
            byte[] keyDigest,
            byte[] requestFingerprint) {

        insertRecord(
                tenantId,
                OPERATION,
                keyDigest,
                requestFingerprint,
                "PROCESSING",
                null,
                null,
                null,
                null);
    }

    private void insertRecord(
            UUID tenantId,
            String operation,
            byte[] keyDigest,
            byte[] requestFingerprint,
            String state,
            UUID orderId,
            String orderStatus,
            String allocationOutcome,
            Timestamp completedAt) {

        jdbcTemplate.update(
                """
                INSERT INTO orders.order_request_idempotency (
                    tenant_id,
                    operation,
                    key_digest,
                    request_fingerprint,
                    state,
                    order_id,
                    order_status,
                    allocation_outcome,
                    created_at,
                    completed_at
                )
                VALUES (
                    ?,
                    ?,
                    ?,
                    ?,
                    ?,
                    ?,
                    ?,
                    ?,
                    CURRENT_TIMESTAMP,
                    ?
                )
                """,
                tenantId,
                operation,
                keyDigest,
                requestFingerprint,
                state,
                orderId,
                orderStatus,
                allocationOutcome,
                completedAt);
    }

    private int rowCount() {

        return jdbcTemplate.queryForObject(
                """
                SELECT COUNT(*)
                FROM orders.order_request_idempotency
                """,
                Integer.class);
    }

    private static byte[] digest(
            int length,
            byte marker) {

        var bytes =
                new byte[length];

        if (length > 0) {
            bytes[0] =
                    marker;
        }

        return bytes;
    }
}

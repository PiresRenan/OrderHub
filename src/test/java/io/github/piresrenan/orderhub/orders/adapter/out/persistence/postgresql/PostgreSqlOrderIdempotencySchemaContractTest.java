package io.github.piresrenan.orderhub.orders.adapter.out.persistence.postgresql;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Locks down the PostgreSQL persistence contract required for durable
 * create-Order idempotency before repository or acquisition behavior exists.
 *
 * <p>
 * This test intentionally exercises Flyway and PostgreSQL directly. It must not
 * depend on an idempotency repository, CreateOrderService or HTTP behavior.
 * </p>
 */
@Testcontainers
class PostgreSqlOrderIdempotencySchemaContractTest {

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

    @Test
    void migratesDurableCreateOrderIdempotencyPersistenceContract() {

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

        var jdbcTemplate =
                new JdbcTemplate(
                        dataSource);

        var tableExists =
                jdbcTemplate.queryForObject(
                        """
                        SELECT EXISTS (
                            SELECT 1
                            FROM information_schema.tables
                            WHERE table_schema = 'orders'
                              AND table_name = 'order_request_idempotency'
                        )
                        """,
                        Boolean.class);

        assertThat(tableExists)
                .as(
                        "V11 must create orders.order_request_idempotency before durable acquisition can exist")
                .isTrue();

        var columns =
                jdbcTemplate.query(
                        """
                        SELECT
                            column_name,
                            data_type,
                            is_nullable
                        FROM information_schema.columns
                        WHERE table_schema = 'orders'
                          AND table_name = 'order_request_idempotency'
                        ORDER BY ordinal_position
                        """,
                        (resultSet, rowNumber) ->
                                new ColumnDefinition(
                                        resultSet.getString(
                                                "column_name"),
                                        resultSet.getString(
                                                "data_type"),
                                        resultSet.getString(
                                                "is_nullable")));

        assertThat(columns)
                .containsExactly(
                        new ColumnDefinition(
                                "tenant_id",
                                "uuid",
                                "NO"),
                        new ColumnDefinition(
                                "operation",
                                "text",
                                "NO"),
                        new ColumnDefinition(
                                "key_digest",
                                "bytea",
                                "NO"),
                        new ColumnDefinition(
                                "request_fingerprint",
                                "bytea",
                                "NO"),
                        new ColumnDefinition(
                                "state",
                                "text",
                                "NO"),
                        new ColumnDefinition(
                                "order_id",
                                "uuid",
                                "YES"),
                        new ColumnDefinition(
                                "order_status",
                                "text",
                                "YES"),
                        new ColumnDefinition(
                                "allocation_outcome",
                                "text",
                                "YES"),
                        new ColumnDefinition(
                                "created_at",
                                "timestamp with time zone",
                                "NO"),
                        new ColumnDefinition(
                                "completed_at",
                                "timestamp with time zone",
                                "YES"));

        var identityColumns =
                jdbcTemplate.queryForList(
                        """
                        SELECT kcu.column_name
                        FROM information_schema.table_constraints tc
                        JOIN information_schema.key_column_usage kcu
                          ON kcu.constraint_catalog = tc.constraint_catalog
                         AND kcu.constraint_schema = tc.constraint_schema
                         AND kcu.constraint_name = tc.constraint_name
                        WHERE tc.table_schema = 'orders'
                          AND tc.table_name = 'order_request_idempotency'
                          AND tc.constraint_type = 'PRIMARY KEY'
                        ORDER BY kcu.ordinal_position
                        """,
                        String.class);

        assertThat(identityColumns)
                .as(
                        "Durable ownership identity must be Tenant + operation + key digest")
                .containsExactly(
                        "tenant_id",
                        "operation",
                        "key_digest");

        var requiredCheckConstraints =
                jdbcTemplate.queryForList(
                        """
                        SELECT tc.constraint_name
                        FROM information_schema.table_constraints tc
                        WHERE tc.table_schema = 'orders'
                          AND tc.table_name = 'order_request_idempotency'
                          AND tc.constraint_type = 'CHECK'
                        ORDER BY tc.constraint_name
                        """,
                        String.class);

        assertThat(requiredCheckConstraints)
                .contains(
                        "ck_order_request_idempotency_key_digest_length",
                        "ck_order_request_idempotency_request_fingerprint_length",
                        "ck_order_request_idempotency_state",
                        "ck_order_request_idempotency_completion");

        var foreignKeyCount =
                jdbcTemplate.queryForObject(
                        """
                        SELECT COUNT(*)
                        FROM information_schema.table_constraints
                        WHERE table_schema = 'orders'
                          AND table_name = 'order_request_idempotency'
                          AND constraint_type = 'FOREIGN KEY'
                        """,
                        Integer.class);

        assertThat(foreignKeyCount)
                .as(
                        "Idempotency storage must not introduce relational coupling to other module tables")
                .isZero();

        var v11MigrationApplied =
                jdbcTemplate.queryForObject(
                        """
                        SELECT EXISTS (
                            SELECT 1
                            FROM flyway_schema_history
                            WHERE success = TRUE
                              AND version = '11'
                              AND script = 'V11__create_order_request_idempotency.sql'
                        )
                        """,
                        Boolean.class);

        assertThat(v11MigrationApplied)
                .as(
                        "Durable idempotency storage must be introduced by the successful V11 migration")
                .isTrue();
    }

    private record ColumnDefinition(
            String name,
            String dataType,
            String nullable) {
    }
}

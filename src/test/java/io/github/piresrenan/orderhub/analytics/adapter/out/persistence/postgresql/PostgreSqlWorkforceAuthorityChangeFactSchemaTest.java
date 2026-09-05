package io.github.piresrenan.orderhub.analytics.adapter.out.persistence.postgresql;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class PostgreSqlWorkforceAuthorityChangeFactSchemaTest {

    private static final DockerImageName POSTGRES_IMAGE =
            DockerImageName.parse(
                    "postgres:18.6-trixie@sha256:"
                            + "4ef4dbc939d61acea57712655ddb4b4ab27419c913f94cca0cd57cb3ea3c2280")
                    .asCompatibleSubstituteFor("postgres");

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(POSTGRES_IMAGE)
                    .withDatabaseName("orderhub_test")
                    .withUsername("orderhub_test")
                    .withPassword("synthetic-test-password");

    private static JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void migrateAcceptedSchemaChain() {

        var dataSource =
                new DriverManagerDataSource(
                        POSTGRES.getJdbcUrl(),
                        POSTGRES.getUsername(),
                        POSTGRES.getPassword());

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        jdbcTemplate =
                new JdbcTemplate(
                        dataSource);
    }

    @Test
    void definesThePrivacySafeWorkforceAuthorityChangeFactRelation() {
        // Why: the analytical fact contract is only durable once PostgreSQL
        // holds it with the same closed shape, so a later change cannot widen
        // analytical storage past what the fact contract admits.
        // Covers: the exact column inventory in storage order, each column's
        // type and nullability, the primary key column sequence, and absence
        // of foreign keys.
        // Prevents: an operational identifier, a payload column, a nullable
        // Tenant scope or a foreign key into operational persistence entering
        // analytical fact storage.
        //
        // Every assertion reads catalog metadata only. The relation is never
        // queried, inserted into or truncated, so its absence surfaces as an
        // empty metadata result and therefore as an assertion failure rather
        // than an SQL error.

        var columns =
                jdbcTemplate.queryForList(
                        """
                        SELECT column_name,
                               data_type,
                               is_nullable
                        FROM information_schema.columns
                        WHERE table_schema = 'analytics'
                          AND table_name = 'workforce_authority_change_facts'
                        ORDER BY ordinal_position
                        """);

        var primaryKeyColumns =
                jdbcTemplate.queryForList(
                        """
                        SELECT key_column.column_name
                        FROM information_schema.table_constraints AS table_constraint
                        JOIN information_schema.key_column_usage AS key_column
                          ON key_column.constraint_schema
                                 = table_constraint.constraint_schema
                         AND key_column.constraint_name
                                 = table_constraint.constraint_name
                        WHERE table_constraint.table_schema = 'analytics'
                          AND table_constraint.table_name
                                  = 'workforce_authority_change_facts'
                          AND table_constraint.constraint_type = 'PRIMARY KEY'
                        ORDER BY key_column.ordinal_position
                        """,
                        String.class);

        var foreignKeys =
                jdbcTemplate.queryForList(
                        """
                        SELECT constraint_name
                        FROM information_schema.table_constraints
                        WHERE table_schema = 'analytics'
                          AND table_name = 'workforce_authority_change_facts'
                          AND constraint_type = 'FOREIGN KEY'
                        """,
                        String.class);

        org.junit.jupiter.api.Assertions.assertAll(
                () -> assertThat(
                        columns.stream()
                                .map(column ->
                                        column.get("column_name"))
                                .toList())
                        .as("Analytical fact storage must carry exactly the"
                                + " bounded fact contract, in storage order")
                        .containsExactly(
                                "tenant_id",
                                "source_event_id",
                                "fact_type",
                                "schema_version",
                                "actor_subject_key",
                                "affected_subject_key",
                                "action",
                                "outcome",
                                "reason_code",
                                "occurred_at"),

                () -> assertThat(
                        columns.stream()
                                .map(column ->
                                        Map.of(
                                                "column_name",
                                                column.get("column_name"),
                                                "data_type",
                                                column.get("data_type"),
                                                "is_nullable",
                                                column.get("is_nullable")))
                                .toList())
                        .as("Each analytical fact column must carry its frozen"
                                + " type and nullability")
                        .containsExactlyElementsOf(
                                expectedColumnMetadata()),

                () -> assertThat(primaryKeyColumns)
                        .as("Tenant scope, source event provenance and"
                                + " analytical projection identity form the"
                                + " arbitration boundary")
                        .containsExactly(
                                "tenant_id",
                                "source_event_id",
                                "fact_type"),

                () -> assertThat(foreignKeys)
                        .as("Analytical fact storage must not reference"
                                + " operational persistence or the identifying"
                                + " subject mapping")
                        .isEmpty());
    }

    private static List<Map<String, Object>> expectedColumnMetadata() {

        return List.of(
                column("tenant_id", "uuid", "NO"),
                column("source_event_id", "uuid", "NO"),
                column("fact_type", "text", "NO"),
                column("schema_version", "integer", "NO"),
                column("actor_subject_key", "uuid", "NO"),
                column("affected_subject_key", "uuid", "NO"),
                column("action", "text", "NO"),
                column("outcome", "text", "NO"),
                column("reason_code", "text", "YES"),
                column("occurred_at", "timestamp with time zone", "NO"));
    }

    private static Map<String, Object> column(
            String name,
            String dataType,
            String nullable) {

        return Map.of(
                "column_name",
                name,
                "data_type",
                dataType,
                "is_nullable",
                nullable);
    }
}

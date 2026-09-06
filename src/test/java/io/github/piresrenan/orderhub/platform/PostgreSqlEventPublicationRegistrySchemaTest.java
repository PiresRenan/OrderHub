package io.github.piresrenan.orderhub.platform;

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
class PostgreSqlEventPublicationRegistrySchemaTest {

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
    void definesTheSpringModulithJdbcEventPublicationRegistryRelation() {
        // Why: the selected durable ingestion mechanism stores its publication
        // log in a relation the framework expects to already exist. Flyway is
        // this repository's only schema authority, so that relation has to be
        // reconstructable from the accepted migration chain rather than created
        // by framework schema initialization at startup.
        // Covers: the relation living in the default schema, its exact ordered
        // column inventory with each column's type and nullability, the
        // publication-identity primary key, both secondary indexes with their
        // access methods, and the absence of foreign keys.
        // Prevents: framework auto-initialization silently owning a relation,
        // the registry landing in an application module's schema, a locally
        // "improved" schema drifting from the framework contract, and the
        // publication log acquiring ownership edges into operational data.
        //
        // Every assertion reads catalog metadata only. The relation is never
        // queried, inserted into or truncated, so its absence surfaces as an
        // empty metadata result and therefore as an assertion failure rather
        // than an SQL error.
        //
        // The expected shape is the Spring Modulith 2.1.1 PostgreSQL schema
        // shipped at
        // org/springframework/modulith/events/jdbc/schemas/v2/schema-postgresql.sql
        // and is deliberately reproduced rather than adapted.

        var columns =
                jdbcTemplate.queryForList(
                        """
                        SELECT column_name,
                               data_type,
                               is_nullable
                        FROM information_schema.columns
                        WHERE table_schema = 'public'
                          AND table_name = 'event_publication'
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
                        WHERE table_constraint.table_schema = 'public'
                          AND table_constraint.table_name = 'event_publication'
                          AND table_constraint.constraint_type = 'PRIMARY KEY'
                        ORDER BY key_column.ordinal_position
                        """,
                        String.class);

        // The primary-key index is excluded deliberately: it is asserted above
        // as a constraint, so this query describes only the two indexes the
        // framework schema adds explicitly.
        var secondaryIndexes =
                jdbcTemplate.queryForList(
                        """
                        SELECT index_class.relname AS index_name,
                               access_method.amname AS access_method,
                               indexed_column.attname AS column_name
                        FROM pg_index AS index_definition
                        JOIN pg_class AS table_class
                          ON table_class.oid = index_definition.indrelid
                        JOIN pg_namespace AS table_namespace
                          ON table_namespace.oid = table_class.relnamespace
                        JOIN pg_class AS index_class
                          ON index_class.oid = index_definition.indexrelid
                        JOIN pg_am AS access_method
                          ON access_method.oid = index_class.relam
                        JOIN pg_attribute AS indexed_column
                          ON indexed_column.attrelid = table_class.oid
                         AND indexed_column.attnum = ANY (index_definition.indkey)
                        WHERE table_namespace.nspname = 'public'
                          AND table_class.relname = 'event_publication'
                          AND NOT index_definition.indisprimary
                        ORDER BY index_class.relname,
                                 indexed_column.attname
                        """);

        var foreignKeys =
                jdbcTemplate.queryForList(
                        """
                        SELECT constraint_name
                        FROM information_schema.table_constraints
                        WHERE table_schema = 'public'
                          AND table_name = 'event_publication'
                          AND constraint_type = 'FOREIGN KEY'
                        """,
                        String.class);

        org.junit.jupiter.api.Assertions.assertAll(
                () -> assertThat(
                        columns.stream()
                                .map(column ->
                                        column.get("column_name"))
                                .toList())
                        .as("Flyway must materialize the framework publication"
                                + " log in the default schema, with its exact"
                                + " column inventory in storage order")
                        .containsExactly(
                                "id",
                                "listener_id",
                                "event_type",
                                "serialized_event",
                                "publication_date",
                                "completion_date",
                                "status",
                                "completion_attempts",
                                "last_resubmission_date"),

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
                        .as("Each publication column must carry the framework's"
                                + " own type and nullability, not a locally"
                                + " stricter variant")
                        .containsExactlyElementsOf(
                                expectedColumnMetadata()),

                () -> assertThat(primaryKeyColumns)
                        .as("Publication identity alone is the primary key")
                        .containsExactly(
                                "id"),

                () -> assertThat(
                        secondaryIndexes.stream()
                                .map(index ->
                                        Map.of(
                                                "index_name",
                                                index.get("index_name"),
                                                "access_method",
                                                index.get("access_method"),
                                                "column_name",
                                                index.get("column_name")))
                                .toList())
                        .as("Both framework secondary indexes must exist with"
                                + " their own access methods")
                        .containsExactlyElementsOf(
                                expectedSecondaryIndexes()),

                () -> assertThat(foreignKeys)
                        .as("The publication log is integration lifecycle state"
                                + " and must not reference operational data")
                        .isEmpty());
    }

    private static List<Map<String, Object>> expectedColumnMetadata() {

        return List.of(
                column("id", "uuid", "NO"),
                column("listener_id", "text", "NO"),
                column("event_type", "text", "NO"),
                column("serialized_event", "text", "NO"),
                column("publication_date", "timestamp with time zone", "NO"),
                column("completion_date", "timestamp with time zone", "YES"),
                column("status", "text", "YES"),
                column("completion_attempts", "integer", "YES"),
                column("last_resubmission_date", "timestamp with time zone", "YES"));
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

    private static List<Map<String, Object>> expectedSecondaryIndexes() {

        return List.of(
                index(
                        "event_publication_by_completion_date_idx",
                        "btree",
                        "completion_date"),
                index(
                        "event_publication_serialized_event_hash_idx",
                        "hash",
                        "serialized_event"));
    }

    private static Map<String, Object> index(
            String indexName,
            String accessMethod,
            String columnName) {

        return Map.of(
                "index_name",
                indexName,
                "access_method",
                accessMethod,
                "column_name",
                columnName);
    }
}

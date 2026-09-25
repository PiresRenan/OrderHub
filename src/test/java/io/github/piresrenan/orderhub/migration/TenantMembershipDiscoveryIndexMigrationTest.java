package io.github.piresrenan.orderhub.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Why: the OH-023 discovery index (ADR-0021) must be a purely additive, forward-only change on both
 * installation paths.
 * Scenario: a database upgraded through the accepted V1-V44 history with existing membership rows, and a
 * fresh database installed from B44; both then apply the pending forward migration.
 * Covers: the exact new index definition, unchanged columns/constraints, preserved business rows and a
 * plan that uses the index for the discovery scan shape.
 * Expected: exactly one additional index on users.tenant_memberships on both paths; rows are untouched.
 * Pinned to target 45 so later forward migrations (V46+) are qualified by their own tests.
 * Prevents: V45 mutating tables or business state, path divergence and the discovery scan regressing to a
 * full-table sequential scan.
 */
@Testcontainers
class TenantMembershipDiscoveryIndexMigrationTest {
    private static final String HISTORY = "classpath:db/migration";
    private static final String BASELINE = "classpath:db/baseline";
    private static final String INDEX = "ix_tenant_memberships_user_status_tenant";

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("postgres:18.6-trixie@sha256:"
                    + "4ef4dbc939d61acea57712655ddb4b4ab27419c913f94cca0cd57cb3ea3c2280")
                    .asCompatibleSubstituteFor("postgres"));

    @Test
    void upgradedHistoryAddsOnlyTheDiscoveryIndexAndPreservesRows() {
        var source = database("discovery_index_history");
        Flyway.configure().dataSource(source).locations(HISTORY).target("44").load().migrate();
        var jdbc = new JdbcTemplate(source);
        var user = UUID.randomUUID(); var tenant = UUID.randomUUID();
        jdbc.update("INSERT INTO users.users (id) VALUES (?)", user);
        jdbc.update("INSERT INTO tenants.tenants (id, name, status) VALUES (?, 'Synthetic index', 'ACTIVE')", tenant);
        jdbc.update("INSERT INTO users.tenant_memberships (user_id, tenant_id, status) VALUES (?, ?, 'SUSPENDED')", user, tenant);
        var columns = columns(jdbc); var constraints = constraints(jdbc); var indexes = indexes(jdbc);
        var rows = jdbc.queryForList("SELECT * FROM users.tenant_memberships");

        var result = Flyway.configure().dataSource(source).locations(HISTORY).target("45").load().migrate();

        assertThat(result.migrationsExecuted).isEqualTo(1);
        assertThat(result.targetSchemaVersion).isEqualTo("45");
        assertThat(columns(jdbc)).isEqualTo(columns);
        assertThat(constraints(jdbc)).isEqualTo(constraints);
        assertThat(indexes(jdbc)).containsAll(indexes).hasSize(indexes.size() + 1);
        assertThat(indexDefinition(jdbc)).isEqualTo(
                "CREATE INDEX " + INDEX + " ON users.tenant_memberships USING btree (user_id, status, tenant_id)");
        assertThat(jdbc.queryForList("SELECT * FROM users.tenant_memberships")).isEqualTo(rows);
        Flyway.configure().dataSource(source).locations(HISTORY).target("45").load().validate();
    }

    @Test
    void freshBaselineInstallationReachesTheSameIndexAndUsesItForTheScan() {
        var source = database("discovery_index_fresh");
        var result = Flyway.configure().dataSource(source).locations(HISTORY, BASELINE).target("45").load().migrate();
        var jdbc = new JdbcTemplate(source);
        assertThat(result.targetSchemaVersion).isEqualTo("45");
        assertThat(jdbc.queryForList("SELECT version, type FROM public.flyway_schema_history ORDER BY installed_rank"))
                .containsExactly(Map.of("version", "44", "type", "SQL_BASELINE"), Map.of("version", "45", "type", "SQL"));
        assertThat(indexDefinition(jdbc)).isEqualTo(
                "CREATE INDEX " + INDEX + " ON users.tenant_memberships USING btree (user_id, status, tenant_id)");
        // Tiny tables favor sequential scans; disabling them asks whether the index is usable for the shape.
        var plan = jdbc.execute((java.sql.Connection connection) -> {
            try (var statement = connection.createStatement()) {
                statement.execute("SET enable_seqscan = off");
                var lines = new StringBuilder();
                try (var rows = statement.executeQuery("""
                        EXPLAIN SELECT tenant_id FROM users.tenant_memberships
                        WHERE user_id = '00000000-0000-0000-0000-000000000001' AND status = 'ACTIVE'
                          AND tenant_id > '00000000-0000-0000-0000-000000000002' ORDER BY tenant_id LIMIT 51
                        """)) {
                    while (rows.next()) { lines.append(rows.getString(1)).append('\n'); }
                }
                return lines.toString();
            }
        });
        assertThat(plan).contains(INDEX).doesNotContain("Sort");
        Flyway.configure().dataSource(source).locations(HISTORY, BASELINE).target("45").load().validate();
    }

    private static String indexDefinition(JdbcTemplate jdbc) {
        return jdbc.queryForObject("SELECT indexdef FROM pg_indexes WHERE schemaname = 'users' AND indexname = ?", String.class, INDEX);
    }

    private static List<Map<String, Object>> indexes(JdbcTemplate jdbc) {
        return jdbc.queryForList("SELECT schemaname, tablename, indexname, indexdef FROM pg_indexes "
                + "WHERE schemaname NOT IN ('pg_catalog', 'information_schema') ORDER BY 1, 2, 3");
    }

    private static List<Map<String, Object>> columns(JdbcTemplate jdbc) {
        return jdbc.queryForList("SELECT table_schema, table_name, column_name, data_type, is_nullable, column_default "
                + "FROM information_schema.columns WHERE table_schema NOT IN ('pg_catalog', 'information_schema') "
                + "AND table_name <> 'flyway_schema_history' ORDER BY 1, 2, 3");
    }

    private static List<Map<String, Object>> constraints(JdbcTemplate jdbc) {
        return jdbc.queryForList("SELECT n.nspname, c.relname, k.conname, pg_catalog.pg_get_constraintdef(k.oid, true) AS def "
                + "FROM pg_catalog.pg_constraint k JOIN pg_catalog.pg_class c ON c.oid = k.conrelid "
                + "JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace "
                + "WHERE n.nspname NOT IN ('pg_catalog', 'information_schema') ORDER BY 1, 2, 3");
    }

    private static DriverManagerDataSource database(String name) {
        var admin = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        // Names are fixed literals in this test; never accept an external identifier here.
        if (!name.matches("[a-z_]+")) { throw new IllegalArgumentException("Invalid test database name"); }
        new JdbcTemplate(admin).execute("CREATE DATABASE " + name);
        return new DriverManagerDataSource("jdbc:postgresql://" + POSTGRES.getHost() + ":"
                + POSTGRES.getMappedPort(5432) + "/" + name, POSTGRES.getUsername(), POSTGRES.getPassword());
    }
}

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
 * Why: V46 (ADR-0022) must be additive and must never seed identity or authority on either installation path.
 * Scenario: an accepted V45 database holding Users, bindings and a Platform grant, and a fresh B44 installation.
 * Covers: exactly one executed migration, untouched pre-existing tables and rows, the single OPEN ceremony row,
 * empty evidence, identical bootstrap schema on both paths, idempotent re-migration and Flyway validation.
 * Prevents: a V46 that reads existing Users as "already bootstrapped", seeds an operator or diverges by path.
 */
@Testcontainers
class FirstOperatorBootstrapMigrationTest {
    private static final String HISTORY = "classpath:db/migration";
    private static final String BASELINE = "classpath:db/baseline";

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("postgres:18.6-trixie@sha256:"
                    + "4ef4dbc939d61acea57712655ddb4b4ab27419c913f94cca0cd57cb3ea3c2280")
                    .asCompatibleSubstituteFor("postgres"));

    @Test
    void acceptedV45DatabaseGainsOnlyTheOpenCeremonyAndKeepsBusinessState() {
        var source = database("first_operator_history");
        Flyway.configure().dataSource(source).locations(HISTORY).target("45").load().migrate();
        var jdbc = new JdbcTemplate(source);
        var user = UUID.randomUUID();
        jdbc.update("INSERT INTO users.users (id) VALUES (?)", user);
        jdbc.update("INSERT INTO users.external_identity_bindings (issuer, subject, user_id) VALUES ('https://legacy.test', 'legacy', ?)", user);
        jdbc.update("""
                INSERT INTO access_control.administrative_grants (grant_id, user_id, scope_type, scope_id, permission_code)
                VALUES (?, ?, 'PLATFORM', NULL, 'PLATFORM_TENANTS_MANAGE')
                """, UUID.randomUUID(), user);
        var before = nonBootstrapState(jdbc);

        var result = Flyway.configure().dataSource(source).locations(HISTORY).load().migrate();

        assertThat(result.migrationsExecuted).isEqualTo(1);
        assertThat(result.targetSchemaVersion).isEqualTo("46");
        assertThat(nonBootstrapState(jdbc)).isEqualTo(before);
        assertOpenAndEmpty(jdbc);
        assertThat(Flyway.configure().dataSource(source).locations(HISTORY).load().migrate().migrationsExecuted).isZero();
        Flyway.configure().dataSource(source).locations(HISTORY).load().validate();
    }

    @Test
    void freshBaselineInstallationReachesTheSameBootstrapSchema() {
        var fresh = database("first_operator_fresh");
        var result = Flyway.configure().dataSource(fresh).locations(HISTORY, BASELINE).load().migrate();
        var historical = database("first_operator_replay");
        Flyway.configure().dataSource(historical).locations(HISTORY).load().migrate();

        assertThat(result.targetSchemaVersion).isEqualTo("46");
        var jdbc = new JdbcTemplate(fresh);
        assertThat(jdbc.queryForList("SELECT version, type FROM public.flyway_schema_history ORDER BY installed_rank")).containsExactly(
                Map.of("version", "44", "type", "SQL_BASELINE"), Map.of("version", "45", "type", "SQL"), Map.of("version", "46", "type", "SQL"));
        assertOpenAndEmpty(jdbc);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users.users", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM access_control.administrative_grants", Long.class)).isZero();
        assertThat(bootstrapSchema(jdbc)).isEqualTo(bootstrapSchema(new JdbcTemplate(historical)));
        Flyway.configure().dataSource(fresh).locations(HISTORY, BASELINE).load().validate();
    }

    private static void assertOpenAndEmpty(JdbcTemplate jdbc) {
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bootstrap.first_operator_ceremony", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT ceremony, state FROM bootstrap.first_operator_ceremony "
                + "WHERE operation_id IS NULL AND operator_user_id IS NULL AND request_fingerprint IS NULL AND completed_at IS NULL")).containsExactly(Map.of(
                "ceremony", "RETAINED_FIRST_OPERATOR_BOOTSTRAP", "state", "OPEN"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bootstrap.first_operator_ceremony_events", Long.class)).isZero();
    }

    private static List<Object> nonBootstrapState(JdbcTemplate jdbc) {
        return List.of(
                jdbc.queryForList("SELECT * FROM users.users"),
                jdbc.queryForList("SELECT * FROM users.external_identity_bindings"),
                jdbc.queryForList("SELECT * FROM access_control.administrative_grants"),
                jdbc.queryForList("SELECT table_schema, table_name, column_name, data_type, is_nullable, column_default "
                        + "FROM information_schema.columns WHERE table_schema NOT IN ('pg_catalog', 'information_schema', 'bootstrap') "
                        + "AND table_name <> 'flyway_schema_history' ORDER BY 1, 2, 3"));
    }

    private static List<Map<String, Object>> bootstrapSchema(JdbcTemplate jdbc) {
        return jdbc.queryForList("""
                SELECT c.relname, k.conname, pg_catalog.pg_get_constraintdef(k.oid, true) AS def
                FROM pg_catalog.pg_constraint k JOIN pg_catalog.pg_class c ON c.oid = k.conrelid
                JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'bootstrap'
                UNION ALL
                SELECT c.relname, t.tgname, pg_catalog.pg_get_triggerdef(t.oid, true)
                FROM pg_catalog.pg_trigger t JOIN pg_catalog.pg_class c ON c.oid = t.tgrelid
                JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace WHERE n.nspname = 'bootstrap' AND NOT t.tgisinternal
                ORDER BY 1, 2
                """);
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

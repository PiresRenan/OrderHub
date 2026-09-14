package io.github.piresrenan.orderhub.migration;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.security.MessageDigest;
import java.util.Arrays;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import com.zaxxer.hikari.HikariConfig;
import com.zaxxer.hikari.HikariDataSource;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.jdbc.datasource.SingleConnectionDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Why: a cumulative fresh-install snapshot must preserve the accepted upgrade history.
 * Covers: the actual Flyway Community resolver, complete PostgreSQL schema, system data,
 * historical checksums and business rows, and a subsequent migration on both paths.
 * Prevents: a convenient fresh install silently diverging from an upgraded installation.
 */
@Testcontainers
class FlywayBaselineMigrationTest {

    private static final String HISTORY_LOCATION = "classpath:db/migration";
    private static final String BASELINE_LOCATION = "classpath:db/baseline";
    private static final String BASELINE_SCRIPT = "B44__orderhub_schema.sql";

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("postgres:18.6-trixie@sha256:"
                    + "4ef4dbc939d61acea57712655ddb4b4ab27419c913f94cca0cd57cb3ea3c2280")
                    .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("orderhub_test")
            .withUsername("orderhub_test")
            .withPassword("synthetic-test-password");

    @Test
    void emptyDatabaseUsesOneBaselineAndValidatesWithoutReplay() {
        var source = database("baseline_empty");
        var flyway = current(source);

        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(1);
        assertThat(new JdbcTemplate(source).queryForList("""
                SELECT version, type, script, success FROM public.flyway_schema_history
                ORDER BY installed_rank
                """))
                .containsExactly(Map.of("version", "44", "type", "SQL_BASELINE",
                        "script", BASELINE_SCRIPT, "success", true));
        flyway.validate();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
        assertThat(new JdbcTemplate(source).queryForObject(
                "SELECT to_regclass('public.event_publication') IS NOT NULL", Boolean.class)).isTrue();
    }

    @Test
    void dumpSessionSettingsDoNotLeakIntoReusedApplicationConnections() throws Exception {
        var source = database("baseline_pool");
        var config = new HikariConfig();
        config.setJdbcUrl(source.getUrl());
        config.setUsername(POSTGRES.getUsername());
        config.setPassword(POSTGRES.getPassword());
        config.setMaximumPoolSize(2);
        config.setMinimumIdle(2);
        config.setConnectionInitSql("""
                SET statement_timeout = '7s'; SET lock_timeout = '3s';
                SET idle_in_transaction_session_timeout = '9s'; SET transaction_timeout = '11s';
                SET check_function_bodies = on; SET row_security = on
                """);
        try (var pool = new HikariDataSource(config)) {
            assertThat(Flyway.configure().dataSource(pool).locations(HISTORY_LOCATION, BASELINE_LOCATION)
                    .target("44").load().migrate().migrationsExecuted).isEqualTo(1);
            // Borrow both at the same time so a fresh untouched connection cannot
            // hide altered session state on Flyway's returned pooled connection.
            try (var first = pool.getConnection(); var second = pool.getConnection()) {
                for (var connection : List.of(first, second)) {
                    var settings = new JdbcTemplate(new SingleConnectionDataSource(connection, true)).queryForMap("""
                            SELECT current_setting('statement_timeout') AS statement_timeout,
                                   current_setting('lock_timeout') AS lock_timeout,
                                   current_setting('idle_in_transaction_session_timeout') AS idle_timeout,
                                   current_setting('transaction_timeout') AS transaction_timeout,
                                   current_setting('check_function_bodies') AS check_bodies,
                                   current_setting('row_security') AS row_security
                            """);
                    assertThat(settings).containsAllEntriesOf(Map.of(
                            "statement_timeout", "7s", "lock_timeout", "3s", "idle_timeout", "9s",
                            "transaction_timeout", "11s", "check_bodies", "on", "row_security", "on"));
                }
            }
        }
    }

    @Test
    void snapshotEqualsTheEntireAcceptedSchemaAndCanonicalPermissionData() throws Exception {
        var historical = database("baseline_equivalence_history");
        var fresh = database("baseline_equivalence_fresh");
        var historicalFlyway = Flyway.configure().dataSource(historical)
                .locations(HISTORY_LOCATION).target("44").load();
        assertThat(historicalFlyway.migrate().migrationsExecuted).isEqualTo(42);
        assertThat(current(fresh).migrate().migrationsExecuted).isEqualTo(1);

        // pg_dump includes schemas, tables, types, all constraint/index definitions,
        // functions, triggers, sequences, comments, defaults and collation declarations.
        assertSchemaEquivalent("baseline_equivalence_history", "baseline_equivalence_fresh");
        // Also compare the physical column order and nullability explicitly; these
        // are easily lost by a hand-maintained schema reconstruction.
        assertThat(columns(fresh)).isEqualTo(columns(historical));
        assertThat(constraints(fresh)).isEqualTo(constraints(historical));
        assertThat(permissions(fresh)).hasSize(24).isEqualTo(permissions(historical));
        assertNoApplicationData(fresh);
        historicalFlyway.validate();
        current(fresh).validate();
    }

    @Test
    void existingV42DatabaseKeepsEveryHistoryRowAndBusinessValue() {
        var source = database("baseline_upgrade");
        Flyway.configure().dataSource(source).locations(HISTORY_LOCATION).target("42").load().migrate();
        var jdbc = new JdbcTemplate(source);
        var user = UUID.fromString("10000000-0000-0000-0000-000000000001");
        var tenant = UUID.fromString("20000000-0000-0000-0000-000000000001");
        var customer = UUID.fromString("30000000-0000-0000-0000-000000000001");
        jdbc.update("INSERT INTO users.users (id) VALUES (?)", user);
        jdbc.update("INSERT INTO users.tenant_memberships (user_id, tenant_id, status) VALUES (?, ?, 'SUSPENDED')", user, tenant);
        jdbc.update("""
                INSERT INTO users.external_identity_bindings (issuer, subject, user_id, active)
                VALUES ('https://synthetic-upgrade.test/Exact', ' Case-Sensitive-Subject ', ?, FALSE)
                """, user);
        jdbc.update("INSERT INTO customers.customer_profiles (tenant_id, customer_id) VALUES (?, ?)", tenant, customer);
        jdbc.update("INSERT INTO customers.customer_account_bindings (tenant_id, customer_id, user_id) VALUES (?, ?, ?)",
                tenant, customer, user);
        var beforeHistory = history(jdbc);
        var beforeBindings = jdbc.queryForList("SELECT * FROM users.external_identity_bindings");
        var beforeMemberships = jdbc.queryForList("SELECT * FROM users.tenant_memberships");
        var beforeCustomers = jdbc.queryForList("SELECT * FROM customers.customer_account_bindings");

        var flyway = current(source);
        assertThat(flyway.migrate().migrationsExecuted).isEqualTo(2);
        var afterHistory = history(jdbc);
        assertThat(afterHistory.subList(0, beforeHistory.size())).isEqualTo(beforeHistory);
        assertThat(jdbc.queryForList("SELECT version FROM public.flyway_schema_history ORDER BY installed_rank", String.class))
                .endsWith("43", "44");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM public.flyway_schema_history WHERE type <> 'SQL'", Integer.class))
                .isZero();
        assertThat(jdbc.queryForList("SELECT * FROM users.external_identity_bindings")).isEqualTo(beforeBindings);
        assertThat(jdbc.queryForList("SELECT * FROM users.tenant_memberships")).isEqualTo(beforeMemberships);
        assertThat(jdbc.queryForList("SELECT * FROM customers.customer_account_bindings")).isEqualTo(beforeCustomers);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users.tenant_membership_events", Integer.class)).isZero();
        flyway.validate();
        assertThat(flyway.migrate().migrationsExecuted).isZero();
    }

    @Test
    void aLaterVersionRunsOnceOnBothFreshAndHistoricalInstallations(@TempDir Path laterMigrations) throws Exception {
        // Freeze this probe's input at 44, so a real future V45 cannot collide
        // with the synthetic V45 or turn this into a different historical test.
        var resources = getClass().getClassLoader();
        try (var manifest = resources.getResourceAsStream("db/baseline/V1-V44.sha256")) {
            assertThat(manifest).isNotNull();
            for (var entry : new String(manifest.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .filter(line -> !line.isBlank() && !line.startsWith("#")).toList()) {
                var filename = entry.split("  ", 2)[1];
                try (var migration = resources.getResourceAsStream("db/migration/" + filename)) {
                    assertThat(migration).as(filename).isNotNull();
                    Files.copy(migration, laterMigrations.resolve(filename));
                }
            }
        }
        Files.writeString(laterMigrations.resolve("V45__synthetic_post_baseline_probe.sql"), """
                CREATE TABLE public.oh021_post_baseline_probe (id INTEGER PRIMARY KEY CHECK (id > 0));
                INSERT INTO public.oh021_post_baseline_probe (id) VALUES (1);
                """, StandardCharsets.UTF_8);
        var fresh = database("baseline_later_fresh");
        var historical = database("baseline_later_history");
        Flyway.configure().dataSource(historical).locations(HISTORY_LOCATION).target("44").load().migrate();

        for (var source : List.of(fresh, historical)) {
            var flyway = Flyway.configure().dataSource(source)
                    .locations(BASELINE_LOCATION, "filesystem:" + laterMigrations.toAbsolutePath())
                    .load();
            assertThat(flyway.migrate().migrationsExecuted).isEqualTo(source == fresh ? 2 : 1);
            assertThat(new JdbcTemplate(source).queryForList("SELECT id FROM public.oh021_post_baseline_probe", Integer.class))
                    .containsExactly(1);
            flyway.validate();
            assertThat(flyway.migrate().migrationsExecuted).isZero();
        }
        assertSchemaEquivalent("baseline_later_history", "baseline_later_fresh");
    }

    @Test
    void theAcceptedMigrationBytesAndHistoricalLocationRemainIntact() throws Exception {
        var resources = getClass().getClassLoader();
        try (var manifest = resources.getResourceAsStream("db/baseline/V1-V44.sha256")) {
            assertThat(manifest).as("accepted V1..V44 byte manifest").isNotNull();
            var entries = new String(manifest.readAllBytes(), StandardCharsets.UTF_8).lines()
                    .filter(line -> !line.isBlank() && !line.startsWith("#")).toList();
            assertThat(entries).hasSize(42);
            for (var entry : entries) {
                var parts = entry.split("  ", 2);
                try (var migration = resources.getResourceAsStream("db/migration/" + parts[1])) {
                    assertThat(migration).as(parts[1]).isNotNull();
                    assertThat(HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(migration.readAllBytes())))
                            .as("accepted bytes: %s", parts[1]).isEqualTo(parts[0]);
                }
            }
        }
        assertThat(resources.getResource("db/migration/" + BASELINE_SCRIPT)).isNull();
        assertThat(resources.getResource("db/baseline/" + BASELINE_SCRIPT)).isNotNull();
    }

    private static Flyway current(DriverManagerDataSource source) {
        return Flyway.configure().dataSource(source).locations(HISTORY_LOCATION, BASELINE_LOCATION).target("44").load();
    }

    private static DriverManagerDataSource database(String name) {
        var admin = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        // Names are fixed literals in this test; never accept an external identifier here.
        if (!name.matches("[a-z_]+")) {
            throw new IllegalArgumentException("Invalid test database name");
        }
        new JdbcTemplate(admin).execute("CREATE DATABASE " + name);
        return new DriverManagerDataSource("jdbc:postgresql://" + POSTGRES.getHost() + ":"
                + POSTGRES.getMappedPort(5432) + "/" + name, POSTGRES.getUsername(), POSTGRES.getPassword());
    }

    private static String schemaDump(String name) throws Exception {
        var dump = POSTGRES.execInContainer("pg_dump", "--username=" + POSTGRES.getUsername(), "--dbname=" + name,
                "--schema-only", "--no-owner", "--no-privileges", "--exclude-table=public.flyway_schema_history");
        assertThat(dump.getExitCode()).as(dump.getStderr()).isZero();
        // Split only LF: String.lines() also treats embedded CR inside SQL
        // string literals as a delimiter and would silently weaken the proof.
        return Arrays.stream(dump.getStdout().split("\n", -1))
                .filter(line -> !line.startsWith("\\restrict ") && !line.startsWith("\\unrestrict "))
                .filter(line -> !line.startsWith("-- Dumped from database version ") && !line.startsWith("-- Dumped by pg_dump version "))
                .collect(Collectors.joining("\n"));
    }

    private static void assertSchemaEquivalent(String historicalName, String freshName) throws Exception {
        var historicalDump = schemaDump(historicalName);
        var canonicalName = historicalName + "_canonical";
        database(canonicalName);
        var restorePath = "/tmp/" + canonicalName + ".sql";
        POSTGRES.copyFileToContainer(Transferable.of(historicalDump.getBytes(StandardCharsets.UTF_8)), restorePath);
        var restore = POSTGRES.execInContainer("psql", "--username=" + POSTGRES.getUsername(),
                "--dbname=" + canonicalName, "--no-psqlrc", "--set=ON_ERROR_STOP=1", "--single-transaction",
                "--file=" + restorePath);
        assertThat(restore.getExitCode()).as(restore.getStderr()).isZero();
        var canonicalDump = schemaDump(canonicalName);
        var freshDump = schemaDump(freshName);
        var artifacts = Files.createDirectories(Path.of("target", "migration-proof"));
        Files.writeString(artifacts.resolve(historicalName + "-original.sql"), historicalDump, StandardCharsets.UTF_8);
        Files.writeString(artifacts.resolve(historicalName + "-canonical.sql"), canonicalDump, StandardCharsets.UTF_8);
        Files.writeString(artifacts.resolve(freshName + ".sql"), freshDump, StandardCharsets.UTF_8);
        // PostgreSQL expands BETWEEN into nested AND nodes. Restoring its dump
        // flattens those associative nodes, so the first and second textual dump
        // differ in redundant parentheses. Let PostgreSQL parse its own output
        // once; never normalize expression text with ad-hoc replacements.
        assertThat(freshDump).isEqualTo(canonicalDump);
    }

    private static List<Map<String, Object>> constraints(DriverManagerDataSource source) {
        return new JdbcTemplate(source).queryForList("""
                SELECT n.nspname, c.relname, k.conname, k.contype, k.condeferrable, k.condeferred, k.convalidated,
                       pg_catalog.pg_get_constraintdef(k.oid, true) AS definition
                FROM pg_catalog.pg_constraint k
                JOIN pg_catalog.pg_class c ON c.oid = k.conrelid
                JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                WHERE n.nspname NOT IN ('pg_catalog', 'information_schema')
                  AND c.relname <> 'flyway_schema_history'
                ORDER BY n.nspname, c.relname, k.conname
                """);
    }

    private static List<Map<String, Object>> columns(DriverManagerDataSource source) {
        return new JdbcTemplate(source).queryForList("""
                SELECT n.nspname, c.relname, a.attname, a.attnum, a.attnotnull, a.attidentity, a.attgenerated,
                       a.attisdropped, pg_catalog.format_type(a.atttypid, a.atttypmod) AS type,
                       pg_catalog.pg_get_expr(d.adbin, d.adrelid) AS default_expression,
                       coll.collname AS collation
                FROM pg_catalog.pg_attribute a
                JOIN pg_catalog.pg_class c ON c.oid = a.attrelid
                JOIN pg_catalog.pg_namespace n ON n.oid = c.relnamespace
                LEFT JOIN pg_catalog.pg_attrdef d ON d.adrelid = c.oid AND d.adnum = a.attnum
                LEFT JOIN pg_catalog.pg_collation coll ON coll.oid = a.attcollation
                WHERE n.nspname NOT IN ('pg_catalog', 'information_schema') AND n.nspname NOT LIKE 'pg_toast%'
                  AND c.relkind IN ('r', 'p', 'v', 'm', 'f') AND a.attnum > 0
                  AND c.relname <> 'flyway_schema_history'
                ORDER BY n.nspname, c.relname, a.attnum
                """);
    }

    private static List<Map<String, Object>> permissions(DriverManagerDataSource source) {
        return new JdbcTemplate(source).queryForList(
                "SELECT code, persona, administrative_scope FROM access_control.permissions ORDER BY code");
    }

    private static List<Map<String, Object>> history(JdbcTemplate jdbc) {
        return jdbc.queryForList("SELECT * FROM public.flyway_schema_history ORDER BY installed_rank");
    }

    private static void assertNoApplicationData(DriverManagerDataSource source) {
        var jdbc = new JdbcTemplate(source);
        var tables = jdbc.queryForList("""
                SELECT quote_ident(schemaname) || '.' || quote_ident(tablename) AS relation
                FROM pg_catalog.pg_tables
                WHERE schemaname NOT IN ('pg_catalog', 'information_schema')
                  AND NOT (schemaname = 'public' AND tablename = 'flyway_schema_history')
                  AND NOT (schemaname = 'access_control' AND tablename = 'permissions')
                """, String.class);
        for (var table : tables) {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM " + table, Long.class)).as(table).isZero();
        }
    }
}

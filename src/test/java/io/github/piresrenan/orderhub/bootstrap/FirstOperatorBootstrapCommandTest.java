package io.github.piresrenan.orderhub.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import io.github.piresrenan.orderhub.OrderHubApplication;
import io.github.piresrenan.orderhub.bootstrap.adapter.in.command.FirstOperatorBootstrapCommand;

/**
 * Why: the only inbound surface of ADR-0022 is an offline, one-shot operating-system process.
 * Scenario: every attempt is a separate JVM launched through {@code OrderHubApplication.main} with the
 * {@code bootstrap-first-operator} argument and hostile, explicitly verbose logger levels. Deployment migrates
 * the databases beforehand, except where a test proves that the command never migrates.
 * Covers: real exit statuses, process restart and replay, bounded input rejection, untrusted issuer, an
 * unavailable database, the exact process-visible output, and command-mode migration isolation (MIG-CMD-1..3).
 * Prevents: logging or stack-trace leakage through explicitly configured loggers, schema mutation by the
 * privileged ceremony, and process-local one-shot state.
 */
@Testcontainers
class FirstOperatorBootstrapCommandTest {

    private static final String TRUSTED = "https://identity.command.test";
    private static final String SUBJECT = "c0mmand-subject-7d1e";
    private static final String PASSWORD = "synthetic-command-password";
    private static final String USERNAME = "command_owner";
    private static final AtomicInteger DATABASES = new AtomicInteger();

    /** Explicit package-level verbosity that a hostile or careless environment might carry. */
    private static final List<String> HOSTILE_LOGGING = List.of(
            "--logging.level.root=TRACE",
            "--logging.level.org.springframework=TRACE",
            "--logging.level.org.springframework.boot=TRACE",
            "--logging.level.com.zaxxer.hikari=TRACE",
            "--logging.level.org.flywaydb=DEBUG",
            "--logging.level.org.postgresql=TRACE",
            "--logging.level.io.github.piresrenan.orderhub=TRACE",
            "--logging.config=classpath:does-not-exist.xml",
            "--debug=true",
            "--trace=true",
            "--spring.main.banner-mode=console");

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("postgres:18.6-trixie@sha256:"
                    + "4ef4dbc939d61acea57712655ddb4b4ab27419c913f94cca0cd57cb3ea3c2280")
                    .asCompatibleSubstituteFor("postgres"))
            .withUsername(USERNAME)
            .withPassword(PASSWORD);

    @TempDir
    Path directory;

    @Test
    void completesOnceAndSeparateProcessesReplayOrRejectWithoutMutation() throws Exception {
        // MIG-CMD-3: an already migrated V46 database runs the normal ceremony.
        var database = migratedDatabase(null);
        var receipt = receipt("{\"issuer\":\"" + TRUSTED + "\",\"subject\":\"" + SUBJECT + "\"}");
        var operation = UUID.randomUUID().toString();

        assertOutcome(run(database, receipt, operation), 0, "COMPLETED");
        var jdbc = jdbc(database);
        var before = snapshot(jdbc);

        assertOutcome(run(database, receipt, operation), 0, "ALREADY_COMPLETED_SAME_OPERATION");
        assertOutcome(run(database, receipt, UUID.randomUUID().toString()), 3, "ALREADY_COMPLETED");
        assertOutcome(run(database, receipt("{\"issuer\":\"" + TRUSTED + "\",\"subject\":\"other\"}"), operation), 3, "ALREADY_COMPLETED");
        assertThat(snapshot(jdbc)).isEqualTo(before);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM users.users", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT permission_code FROM access_control.administrative_grants", String.class))
                .containsExactly("PLATFORM_TENANTS_MANAGE");
        assertThat(history(jdbc)).containsExactly("44", "45", "46");
    }

    @Test
    void rejectsUnusableInputAndUntrustedIssuerWithoutMutation() throws Exception {
        var database = migratedDatabase(null);
        var operation = UUID.randomUUID().toString();
        var valid = receipt("{\"issuer\":\"" + TRUSTED + "\",\"subject\":\"" + SUBJECT + "\"}");

        assertOutcome(run(database, receipt("{\"issuer\":\"" + TRUSTED + "\",\"subject\":\"" + SUBJECT + "\",\"password\":\"x\"}"),
                operation), 2, "INVALID_INPUT");
        assertOutcome(run(database, directory.resolve("missing.json"), operation), 2, "INVALID_INPUT");
        assertOutcome(run(database, valid, operation.toUpperCase()), 2, "INVALID_INPUT");
        assertOutcome(run(database, valid, null), 2, "INVALID_INPUT");
        assertOutcome(run(database, receipt("{\"issuer\":\"https://attacker.example.test\",\"subject\":\"" + SUBJECT + "\"}"),
                operation), 4, "UNTRUSTED_ISSUER");

        var jdbc = jdbc(database);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users.users", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM access_control.administrative_grants", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT state FROM bootstrap.first_operator_ceremony", String.class)).isEqualTo("OPEN");
    }

    @Test
    void unavailableDatabaseUnderHostileLoggingPrintsOnlyTheOutcome() throws Exception {
        var receipt = receipt("{\"issuer\":\"" + TRUSTED + "\",\"subject\":\"" + SUBJECT + "\"}");
        var args = arguments("jdbc:postgresql://127.0.0.1:1/unreachable_bootstrap_db", receipt, UUID.randomUUID().toString());
        args.add("--spring.datasource.hikari.connection-timeout=250");

        var result = launch(args);

        assertOutcome(result, 1, "PERSISTENCE_FAILURE");
        assertThat(result.output()).doesNotContain(SUBJECT, PASSWORD, USERNAME, TRUSTED, receipt.toString(), "127.0.0.1",
                "unreachable", "jdbc:", "Exception", "\tat ", "Hikari", "Flyway", "PSQL", "APPLICATION FAILED", "SELECT", "DEBUG",
                "TRACE", "ERROR", "WARN");
    }

    @Test
    void commandNeverMigratesAV45DatabaseAndFailsBoundedWithoutMutation() throws Exception {
        // MIG-CMD-1: valid input against a database the deployment has not migrated to V46.
        var database = migratedDatabase("45");
        var receipt = receipt("{\"issuer\":\"" + TRUSTED + "\",\"subject\":\"" + SUBJECT + "\"}");

        assertOutcome(run(database, receipt, UUID.randomUUID().toString()), 1, "PERSISTENCE_FAILURE");

        // MIG-CMD-2: structurally invalid input is classified without touching the schema either.
        assertOutcome(run(database, receipt("{\"issuer\":1}"), UUID.randomUUID().toString()), 2, "INVALID_INPUT");

        var jdbc = jdbc(database);
        assertThat(history(jdbc)).containsExactly("44", "45");
        assertThat(jdbc.queryForObject("SELECT to_regnamespace('bootstrap')::text", String.class)).isNull();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users.users", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users.external_identity_bindings", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM access_control.administrative_grants", Long.class)).isZero();
    }

    private ProcessResult run(String database, Path receipt, String operation) throws Exception {
        return launch(arguments(url(database), receipt, operation));
    }

    /** Launches the real entry point in a separate JVM and captures everything the process writes. */
    private ProcessResult launch(List<String> args) throws IOException, InterruptedException {
        var command = new ArrayList<String>(List.of(
                Path.of(System.getProperty("java.home"), "bin", "java").toString(),
                "-cp", System.getProperty("java.class.path"),
                OrderHubApplication.class.getName(), FirstOperatorBootstrapCommand.NAME));
        command.addAll(args);
        var process = new ProcessBuilder(command).redirectErrorStream(true).start();
        var output = new String(process.getInputStream().readAllBytes(), StandardCharsets.UTF_8);
        assertThat(process.waitFor(120, TimeUnit.SECONDS)).as("command process terminated").isTrue();
        return new ProcessResult(process.exitValue(), output);
    }

    private static List<String> arguments(String url, Path receipt, String operation) {
        var args = new ArrayList<>(List.of(
                "--spring.datasource.url=" + url,
                "--spring.datasource.username=" + USERNAME,
                "--spring.datasource.password=" + PASSWORD,
                "--orderhub.security.jwt.token-profile=GENERIC",
                "--orderhub.security.jwt.issuer=" + TRUSTED,
                "--orderhub.security.jwt.audience=orderhub-api-test",
                "--orderhub.security.jwt.jwk-set-uri=http://127.0.0.1:1/test-only-jwks",
                "--" + FirstOperatorBootstrapCommand.RECEIPT_FILE + "=" + receipt));
        if (operation != null) {
            args.add("--" + FirstOperatorBootstrapCommand.OPERATION_ID + "=" + operation);
        }
        args.addAll(HOSTILE_LOGGING);
        return args;
    }

    /** The complete process-visible output is exactly one outcome line. */
    private static void assertOutcome(ProcessResult result, int exitCode, String outcome) {
        assertThat(result.output().strip()).as("process output").isEqualTo("FIRST_OPERATOR_BOOTSTRAP: " + outcome);
        assertThat(result.exitCode()).as(outcome).isEqualTo(exitCode);
    }

    private Path receipt(String content) throws IOException {
        var file = Files.createTempFile(directory, "receipt", ".json");
        Files.writeString(file, content, StandardCharsets.UTF_8);
        return file;
    }

    private static List<Object> snapshot(JdbcTemplate jdbc) {
        return List.of(
                jdbc.queryForList("SELECT * FROM users.users ORDER BY id"),
                jdbc.queryForList("SELECT * FROM users.external_identity_bindings"),
                jdbc.queryForList("SELECT * FROM access_control.administrative_grants"),
                jdbc.queryForList("SELECT * FROM bootstrap.first_operator_ceremony"),
                jdbc.queryForList("SELECT * FROM bootstrap.first_operator_ceremony_events"));
    }

    private static List<String> history(JdbcTemplate jdbc) {
        return jdbc.queryForList("SELECT version FROM public.flyway_schema_history ORDER BY installed_rank", String.class);
    }

    /** Plays the deployment migration step: fresh installation through the production locations. */
    private static String migratedDatabase(String target) {
        var name = "command_case_" + DATABASES.incrementAndGet();
        new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), USERNAME, PASSWORD)).execute("CREATE DATABASE " + name);
        var flyway = Flyway.configure().dataSource(url(name), USERNAME, PASSWORD).locations("classpath:db/migration", "classpath:db/baseline");
        if (target != null) {
            flyway.target(target);
        }
        flyway.load().migrate();
        return name;
    }

    private static JdbcTemplate jdbc(String database) {
        return new JdbcTemplate(new DriverManagerDataSource(url(database), USERNAME, PASSWORD));
    }

    private static String url(String database) {
        return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/" + database;
    }

    private record ProcessResult(int exitCode, String output) {
    }
}

package io.github.piresrenan.orderhub.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import io.github.piresrenan.orderhub.OrderHubApplication;
import io.github.piresrenan.orderhub.bootstrap.adapter.in.command.FirstOperatorBootstrapCommand;
import io.github.piresrenan.orderhub.bootstrap.adapter.in.command.FirstOperatorBootstrapCommand.Result;

/**
 * Why: the only inbound surface of ADR-0022 is an offline, one-shot process.
 * Scenario: the real application class is launched in command mode, without a web server, against a fresh
 * PostgreSQL database that the command itself installs through the production Flyway locations (B44 + V45 + V46).
 * Covers: exit results, separate-process restart and replay, strict receipt/operation validation, untrusted
 * issuer, unavailable database and the absence of identity or configuration values in all captured output.
 * Prevents: bootstrap inputs on the command line, value echoing and process-local one-shot state.
 */
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class FirstOperatorBootstrapCommandTest {

    private static final String TRUSTED = "https://identity.command.test";
    private static final String SUBJECT = "c0mmand-subject-7d1e";
    private static final String PASSWORD = "synthetic-command-password";
    private static final AtomicInteger DATABASES = new AtomicInteger();

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("postgres:18.6-trixie@sha256:"
                    + "4ef4dbc939d61acea57712655ddb4b4ab27419c913f94cca0cd57cb3ea3c2280")
                    .asCompatibleSubstituteFor("postgres"))
            .withPassword(PASSWORD);

    @TempDir
    Path directory;

    @AfterEach
    void restoreSharedTestJvmLogging() {
        // The command switches logging off for its own process; this test JVM hosts other suites.
        LoggingSystem.get(getClass().getClassLoader()).setLogLevel(LoggingSystem.ROOT_LOGGER_NAME, LogLevel.INFO);
    }

    @Test
    void completesOnceAndSeparateProcessesReplayOrRejectWithoutMutation(CapturedOutput output) throws Exception {
        var database = freshDatabase();
        var receipt = receipt("{\"issuer\":\"" + TRUSTED + "\",\"subject\":\"" + SUBJECT + "\"}");
        var operation = UUID.randomUUID().toString();

        assertThat(run(database, receipt, operation)).isEqualTo(Result.COMPLETED);
        var jdbc = jdbc(database);
        var before = snapshot(jdbc);
        assertThat(before.toString()).contains("COMPLETED");

        // Each run is a new application context and connection pool: nothing survives in memory.
        assertThat(run(database, receipt, operation)).isEqualTo(Result.ALREADY_COMPLETED_SAME_OPERATION);
        assertThat(run(database, receipt, UUID.randomUUID().toString())).isEqualTo(Result.ALREADY_COMPLETED);
        assertThat(run(database, receipt("{\"issuer\":\"" + TRUSTED + "\",\"subject\":\"other\"}"), operation))
                .isEqualTo(Result.ALREADY_COMPLETED);
        assertThat(snapshot(jdbc)).isEqualTo(before);

        assertThat(jdbc.queryForObject("SELECT count(*) FROM users.users", Long.class)).isEqualTo(1);
        assertThat(jdbc.queryForList("SELECT permission_code FROM access_control.administrative_grants", String.class))
                .containsExactly("PLATFORM_TENANTS_MANAGE");
        assertThat(jdbc.queryForList("SELECT version FROM public.flyway_schema_history ORDER BY installed_rank", String.class))
                .containsExactly("44", "45", "46");
        assertNoSensitiveOutput(output, receipt);
        assertThat(outcomeLines).containsExactly(
                "FIRST_OPERATOR_BOOTSTRAP: COMPLETED",
                "FIRST_OPERATOR_BOOTSTRAP: ALREADY_COMPLETED_SAME_OPERATION",
                "FIRST_OPERATOR_BOOTSTRAP: ALREADY_COMPLETED",
                "FIRST_OPERATOR_BOOTSTRAP: ALREADY_COMPLETED");
        assertThat(Result.COMPLETED.exitCode()).isZero();
        assertThat(Result.ALREADY_COMPLETED.exitCode()).isEqualTo(3);
    }

    @Test
    void rejectsUnusableInputAndUntrustedIssuerWithoutMutation(CapturedOutput output) throws Exception {
        var database = freshDatabase();
        var operation = UUID.randomUUID().toString();
        var invalid = List.of(
                "", "[]", "null", "{\"issuer\":\"" + TRUSTED + "\"}",
                "{\"issuer\":\"" + TRUSTED + "\",\"subject\":\"" + SUBJECT + "\",\"password\":\"x\"}",
                "{\"issuer\":\"" + TRUSTED + "\",\"subject\":\"a\",\"subject\":\"" + SUBJECT + "\"}",
                "{\"issuer\":\"" + TRUSTED + "\",\"subject\":42}",
                "{\"issuer\":\"" + TRUSTED + "\",\"subject\":null}",
                "{\"issuer\":\"" + TRUSTED + "\",\"subject\":\" " + SUBJECT + "\"}",
                "{\"issuer\":\"" + TRUSTED + " \",\"subject\":\"" + SUBJECT + "\"}",
                "{\"issuer\":\"" + TRUSTED + "\",\"subject\":\"a\\u0000b\"}",
                "{\"issuer\":\"" + TRUSTED + "\",\"subject\":\"" + "s".repeat(1025) + "\"}");
        for (var content : invalid) {
            assertThat(run(database, receipt(content), operation)).as(content).isEqualTo(Result.INVALID_INPUT);
        }
        var malformedUtf8 = directory.resolve("malformed.json");
        Files.write(malformedUtf8, new byte[] {'{', '"', (byte) 0xC3, '"', '}'});
        assertThat(run(database, malformedUtf8, operation)).isEqualTo(Result.INVALID_INPUT);
        var oversized = receipt("{\"issuer\":\"" + TRUSTED + "\",\"subject\":\"" + SUBJECT + "\"}" + " ".repeat(20_000));
        assertThat(run(database, oversized, operation)).isEqualTo(Result.INVALID_INPUT);

        var valid = receipt("{\"issuer\":\"" + TRUSTED + "\",\"subject\":\"" + SUBJECT + "\"}");
        assertThat(run(database, directory.resolve("missing.json"), operation)).isEqualTo(Result.INVALID_INPUT);
        assertThat(run(database, valid, "not-a-uuid")).isEqualTo(Result.INVALID_INPUT);
        assertThat(run(database, valid, operation.toUpperCase())).isEqualTo(Result.INVALID_INPUT);
        assertThat(run(database, valid, null)).isEqualTo(Result.INVALID_INPUT);
        assertThat(run(database, receipt("{\"issuer\":\"https://attacker.example.test\",\"subject\":\"" + SUBJECT + "\"}"), operation))
                .isEqualTo(Result.UNTRUSTED_ISSUER);

        var jdbc = jdbc(database);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users.users", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM access_control.administrative_grants", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT state FROM bootstrap.first_operator_ceremony", String.class)).isEqualTo("OPEN");
        assertNoSensitiveOutput(output, valid);
    }

    @Test
    void unavailableDatabaseIsABoundedFailure(CapturedOutput output) throws Exception {
        var receipt = receipt("{\"issuer\":\"" + TRUSTED + "\",\"subject\":\"" + SUBJECT + "\"}");
        var args = arguments("jdbc:postgresql://127.0.0.1:1/unreachable", receipt, UUID.randomUUID().toString());
        args.add("--spring.datasource.hikari.initialization-fail-timeout=1");
        args.add("--spring.datasource.hikari.connection-timeout=250");

        assertThat(invoke(args)).isEqualTo(Result.PERSISTENCE_FAILURE);
        // The command boundary is value-free: no stack trace, driver, pool, migration or connection detail.
        assertThat(output.getAll()).doesNotContain(SUBJECT, PASSWORD, TRUSTED, receipt.toString(), POSTGRES.getUsername(),
                "127.0.0.1", "unreachable", "jdbc:", "5432", "Connection", "Exception", "	at ", "Hikari", "Flyway",
                "PSQL", "APPLICATION FAILED", "SELECT", "INSERT");
        assertThat(output.getAll().strip()).isEmpty();
        assertThat(outcomeLines).containsExactly("FIRST_OPERATOR_BOOTSTRAP: PERSISTENCE_FAILURE");
    }

    private final List<String> outcomeLines = new ArrayList<>();

    private Result run(String database, Path receipt, String operation) {
        return invoke(arguments(url(database), receipt, operation));
    }

    private Result invoke(List<String> args) {
        var buffer = new ByteArrayOutputStream();
        var result = FirstOperatorBootstrapCommand.run(OrderHubApplication.class, args.toArray(String[]::new),
                new PrintStream(buffer, true, StandardCharsets.UTF_8));
        outcomeLines.add(buffer.toString(StandardCharsets.UTF_8).strip());
        return result;
    }

    private static List<String> arguments(String url, Path receipt, String operation) {
        var args = new ArrayList<>(List.of(
                "--spring.datasource.url=" + url,
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + PASSWORD,
                "--orderhub.security.jwt.token-profile=GENERIC",
                "--orderhub.security.jwt.issuer=" + TRUSTED,
                "--orderhub.security.jwt.audience=orderhub-api-test",
                "--orderhub.security.jwt.jwk-set-uri=http://127.0.0.1:1/test-only-jwks",
                "--" + FirstOperatorBootstrapCommand.RECEIPT_FILE + "=" + receipt));
        if (operation != null) {
            args.add("--" + FirstOperatorBootstrapCommand.OPERATION_ID + "=" + operation);
        }
        return args;
    }

    private void assertNoSensitiveOutput(CapturedOutput output, Path receipt) {
        assertThat(output.getAll()).isEmpty();
        assertThat(String.join("\n", outcomeLines)).doesNotContain(SUBJECT, TRUSTED, receipt.toString());
    }

    private Path receipt(String content) throws Exception {
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

    private static String freshDatabase() {
        var name = "command_case_" + DATABASES.incrementAndGet();
        new JdbcTemplate(new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), PASSWORD))
                .execute("CREATE DATABASE " + name);
        return name;
    }

    private static JdbcTemplate jdbc(String database) {
        return new JdbcTemplate(new DriverManagerDataSource(url(database), POSTGRES.getUsername(), PASSWORD));
    }

    private static String url(String database) {
        return "jdbc:postgresql://" + POSTGRES.getHost() + ":" + POSTGRES.getMappedPort(5432) + "/" + database;
    }
}

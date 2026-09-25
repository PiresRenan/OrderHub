package io.github.piresrenan.orderhub.bootstrap.adapter.in.command;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;
import java.util.stream.Stream;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.logging.LogLevel;
import org.springframework.boot.logging.LoggingSystem;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import io.github.piresrenan.orderhub.OrderHubApplication;
import io.github.piresrenan.orderhub.bootstrap.application.port.in.BootstrapFirstOperatorUseCase;

/**
 * Why: the command must be a bounded one-shot process, never a server.
 * Covers: non-web context, no web server, one attempt, closed context before return, value-free output,
 * and System.exit confined to the entry point so the command stays testable.
 * Prevents: a lingering listener or context, logging leakage and hidden process termination.
 */
@Testcontainers
@ExtendWith(OutputCaptureExtension.class)
class FirstOperatorBootstrapCommandModeTest {

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("postgres:18.6-trixie@sha256:"
                    + "4ef4dbc939d61acea57712655ddb4b4ab27419c913f94cca0cd57cb3ea3c2280")
                    .asCompatibleSubstituteFor("postgres"));

    @TempDir
    Path directory;

    @AfterEach
    void restoreSharedTestJvmLogging() {
        // The command switches logging off for its own process; this test JVM hosts other suites.
        LoggingSystem.get(getClass().getClassLoader()).setLogLevel(LoggingSystem.ROOT_LOGGER_NAME, LogLevel.INFO);
    }

    @Test
    void commandModeIsANonWebOneShotContextThatIsClosedBeforeReturning(CapturedOutput output) throws Exception {
        var receipt = directory.resolve("receipt.json");
        Files.writeString(receipt, "{\"issuer\":\"https://identity.mode.test\",\"subject\":\"mode-subject\"}", StandardCharsets.UTF_8);
        var observed = new AtomicReference<ConfigurableApplicationContext>();
        var buffer = new ByteArrayOutputStream();

        var result = FirstOperatorBootstrapCommand.run(OrderHubApplication.class, new String[] {
                "--spring.datasource.url=" + POSTGRES.getJdbcUrl(),
                "--spring.datasource.username=" + POSTGRES.getUsername(),
                "--spring.datasource.password=" + POSTGRES.getPassword(),
                "--orderhub.security.jwt.token-profile=GENERIC",
                "--orderhub.security.jwt.issuer=https://identity.mode.test",
                "--orderhub.security.jwt.audience=orderhub-api-test",
                "--orderhub.security.jwt.jwk-set-uri=http://127.0.0.1:1/test-only-jwks",
                "--" + FirstOperatorBootstrapCommand.RECEIPT_FILE + "=" + receipt,
                "--" + FirstOperatorBootstrapCommand.OPERATION_ID + "=" + UUID.randomUUID()
        }, new PrintStream(buffer, true, StandardCharsets.UTF_8), context -> {
            observed.set(context);
            assertThat(context).isNotInstanceOf(WebServerApplicationContext.class);
            assertThat(context.getBeanNamesForType(BootstrapFirstOperatorUseCase.class)).hasSize(1);
        });

        assertThat(result).isEqualTo(FirstOperatorBootstrapCommand.Result.COMPLETED);
        assertThat(observed.get()).isNotNull();
        assertThat(observed.get().isActive()).isFalse();
        assertThat(buffer.toString(StandardCharsets.UTF_8)).isEqualTo("FIRST_OPERATOR_BOOTSTRAP: COMPLETED" + System.lineSeparator());
        assertThat(output.getAll()).isEmpty();
    }

    @Test
    void onlyTheEntryPointTerminatesTheProcess() throws IOException {
        try (Stream<Path> sources = Files.walk(Path.of("src", "main", "java"))) {
            assertThat(sources.filter(path -> path.toString().endsWith(".java")).filter(path -> read(path).contains("System.exit"))
                    .map(path -> path.getFileName().toString())).containsExactly("OrderHubApplication.java");
        }
    }

    private static String read(Path path) {
        try {
            return Files.readString(path);
        } catch (IOException exception) {
            throw new IllegalStateException(exception);
        }
    }
}

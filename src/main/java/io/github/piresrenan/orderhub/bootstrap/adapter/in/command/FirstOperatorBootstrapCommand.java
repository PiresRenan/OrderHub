package io.github.piresrenan.orderhub.bootstrap.adapter.in.command;

import java.io.PrintStream;
import java.nio.file.Path;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Consumer;

import org.springframework.boot.Banner;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.context.event.ApplicationEnvironmentPreparedEvent;
import org.springframework.context.ApplicationListener;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.core.Ordered;
import org.springframework.core.env.Environment;
import org.springframework.core.env.MapPropertySource;

import io.github.piresrenan.orderhub.bootstrap.application.port.in.BootstrapFirstOperatorUseCase;
import io.github.piresrenan.orderhub.bootstrap.application.port.in.FirstOperatorBootstrapOutcome;

/**
 * Offline, one-shot command mode for the retained first-operator bootstrap (ADR-0022).
 *
 * <p>The command starts the application context without a web server, performs
 * exactly one ceremony attempt, closes the context and returns a stable result.
 * Its only output is one line {@code FIRST_OPERATOR_BOOTSTRAP: <RESULT>}.
 * Before the logging system initializes, the command installs a logging
 * configuration whose only appender discards every event. No stack trace,
 * connection detail, SQL or identity value can reach the operator output, even
 * when logger levels are configured explicitly. The command never migrates
 * the schema. It is reachable only through
 * the explicit {@link #NAME} launch argument; a normal server start never
 * reads its configuration or invokes the ceremony.</p>
 *
 * <p>Issuer and subject come only from a receipt file named by configuration,
 * never from positional arguments that would appear in process listings.</p>
 */
public final class FirstOperatorBootstrapCommand {

    /** First launch argument that selects this command instead of the HTTP server. */
    public static final String NAME = "bootstrap-first-operator";

    /** Path of the deployment-owned Identity receipt file. */
    public static final String RECEIPT_FILE = "orderhub.bootstrap.first-operator.receipt-file";

    /** OrderHub ceremony operation identifier (UUID); reuse it verbatim only to rerun the same ceremony. */
    public static final String OPERATION_ID = "orderhub.bootstrap.first-operator.operation-id";

    /** Output prefix of the single outcome line. */
    static final String OUTPUT_PREFIX = "FIRST_OPERATOR_BOOTSTRAP: ";

    /** Command-only Logback configuration whose single appender discards every event. */
    static final String LOGGING_CONFIG = "classpath:io/github/piresrenan/orderhub/bootstrap/first-operator-bootstrap-logback.xml";

    /**
     * Command-mode settings that take precedence over every other configuration source.
     *
     * <ul>
     * <li>Logging uses the discarding configuration, so explicitly configured logger levels have no output.</li>
     * <li>Flyway is disabled: schema migration belongs to the deployment migration step, and V46 must already
     * exist. On an older schema the ceremony fails without mutating anything.</li>
     * <li>The only current background mutators (outstanding-event republication and analytics housekeeping)
     * are disabled.</li>
     * </ul>
     */
    private static final Map<String, Object> COMMAND_OVERRIDES = Map.of(
            "logging.config", LOGGING_CONFIG,
            "logging.level.root", "OFF",
            "spring.main.banner-mode", "off",
            "spring.main.log-startup-info", "false",
            "spring.flyway.enabled", "false",
            "spring.modulith.events.republish-outstanding-events-on-restart", "false",
            "orderhub.analytics.housekeeping.enabled", "false");

    private FirstOperatorBootstrapCommand() {
    }

    /** Stable process results; names are the only text the command prints. */
    public enum Result {
        COMPLETED(0),
        ALREADY_COMPLETED_SAME_OPERATION(0),
        PERSISTENCE_FAILURE(1),
        INVALID_INPUT(2),
        ALREADY_COMPLETED(3),
        UNTRUSTED_ISSUER(4),
        INCOMPATIBLE_EXISTING_STATE(5);

        private final int exitCode;

        Result(int exitCode) {
            this.exitCode = exitCode;
        }

        /**
         * Process exit status for this result.
         *
         * @return stable exit status
         */
        public int exitCode() {
            return exitCode;
        }

        /** Maps a ceremony outcome to the equally named process result. */
        static Result of(FirstOperatorBootstrapOutcome outcome) {
            return valueOf(outcome.name());
        }
    }

    /**
     * Runs one bootstrap attempt in a non-web application context. Never terminates the JVM;
     * the entry point maps the returned result to the process exit status after the context is closed.
     *
     * @param application Spring Boot application source
     * @param args        remaining launch arguments (Spring configuration only)
     * @param out         destination of the single outcome line
     * @return the classified result
     */
    public static Result run(Class<?> application, String[] args, PrintStream out) {
        return run(application, args, out, context -> { });
    }

    /**
     * Runs one attempt and lets a caller observe the started context before the attempt; used to
     * prove the command-mode context shape.
     *
     * @param application Spring Boot application source
     * @param args        remaining launch arguments
     * @param out         destination of the single outcome line
     * @param inspector   observer of the started context
     * @return the classified result
     */
    static Result run(Class<?> application, String[] args, PrintStream out, Consumer<ConfigurableApplicationContext> inspector) {
        Objects.requireNonNull(application, "application");
        Objects.requireNonNull(out, "out");
        Objects.requireNonNull(inspector, "inspector");
        var result = execute(application, args == null ? new String[0] : args, inspector);
        out.println(OUTPUT_PREFIX + result.name());
        out.flush();
        return result;
    }

    /** Starts the context, performs the attempt and always closes the context; failure details are never echoed. */
    private static Result execute(Class<?> application, String[] args, Consumer<ConfigurableApplicationContext> inspector) {
        var app = new SpringApplication(application);
        app.setWebApplicationType(WebApplicationType.NONE);
        app.setBannerMode(Banner.Mode.OFF);
        app.setLogStartupInfo(false);
        app.addListeners(new CommandOverrides());
        try (var context = app.run(args)) {
            inspector.accept(context);
            var request = FirstOperatorReceiptReader.read(receipt(context.getEnvironment()), operationId(context.getEnvironment()));
            return Result.of(context.getBean(BootstrapFirstOperatorUseCase.class).bootstrap(request));
        } catch (InvalidBootstrapInputException exception) {
            return Result.INVALID_INPUT;
        } catch (RuntimeException exception) {
            // Startup, lock-timeout and persistence failures: the transaction committed nothing.
            // The cause is deliberately not rendered; it can carry connection details or SQL.
            return Result.PERSISTENCE_FAILURE;
        }
    }

    /** Resolves the receipt path without echoing it. */
    private static Path receipt(Environment environment) {
        var value = environment.getProperty(RECEIPT_FILE);
        if (value == null || value.isBlank()) {
            throw new InvalidBootstrapInputException();
        }
        try {
            return Path.of(value);
        } catch (RuntimeException exception) {
            throw new InvalidBootstrapInputException();
        }
    }

    /** Requires a canonical UUID so reruns compare the same ceremony identity. */
    private static UUID operationId(Environment environment) {
        var value = environment.getProperty(OPERATION_ID);
        try {
            var parsed = UUID.fromString(Objects.requireNonNull(value));
            if (!parsed.toString().equals(value)) {
                throw new InvalidBootstrapInputException();
            }
            return parsed;
        } catch (RuntimeException exception) {
            throw new InvalidBootstrapInputException();
        }
    }

    /**
     * Installs the command overrides as the highest-precedence property source after configuration
     * post-processing and before the logging system reads its levels.
     */
    private static final class CommandOverrides implements ApplicationListener<ApplicationEnvironmentPreparedEvent>, Ordered {

        /** Places the overrides first so no external source can re-enable logging or background work. */
        @Override
        public void onApplicationEvent(ApplicationEnvironmentPreparedEvent event) {
            event.getEnvironment().getPropertySources()
                    .addFirst(new MapPropertySource("orderhub-first-operator-bootstrap-command", COMMAND_OVERRIDES));
        }

        /** Runs after environment post-processors (HIGHEST+10) and before logging (HIGHEST+20). */
        @Override
        public int getOrder() {
            return Ordered.HIGHEST_PRECEDENCE + 15;
        }
    }
}

package io.github.piresrenan.orderhub.platform;

import static org.awaitility.Awaitility.await;
import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;

import io.github.piresrenan.orderhub.support.PostgreSqlTestConfiguration;

/**
 * Verifies that database availability affects traffic eligibility without
 * incorrectly affecting process-level liveness.
 */
@SpringBootTest(properties = {
        "spring.datasource.hikari.connection-timeout=1000"
})
@AutoConfigureMockMvc
@Import(PostgreSqlTestConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
class PlatformDatabaseReadinessTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PostgreSQLContainer postgresContainer;

    /**
     * Verifies that a running application becomes unready, but remains live,
     * after its required PostgreSQL dependency becomes unavailable.
     *
     * @param output captured application output used to verify outage log
     *               sanitization
     * @throws Exception when MockMvc cannot execute a probe request
     */
    @Test
    void becomesUnreadyButRemainsLiveWhenPostgreSqlIsUnavailable(
            CapturedOutput output)
            throws Exception {

        // Why: Orders cannot be created reliably while their durable database is
        // unavailable, and the resulting outage must not leak infrastructure details.
        // Covers: database-aware readiness, process-oriented liveness and sanitized
        // logging after a runtime PostgreSQL outage.
        // Prevents: routing traffic to an unusable replica and exposing JDBC,
        // connection or stack-trace internals during dependency failures.

        mockMvc.perform(get("/readyz"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));

        mockMvc.perform(get("/livez"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"));

        var outageLogStart = output.getAll().length();

        postgresContainer.stop();

        await()
                .atMost(Duration.ofSeconds(5))
                .pollInterval(Duration.ofMillis(250))
                .untilAsserted(() -> mockMvc.perform(get("/readyz"))
                        .andExpect(status().isServiceUnavailable())
                        .andExpect(jsonPath("$.status").value("DOWN"))
                        .andExpect(jsonPath("$.components").doesNotExist()));

        mockMvc.perform(get("/livez"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());

        var completeOutput = output.getAll();
        var outageOutput = completeOutput.substring(
                Math.min(outageLogStart, completeOutput.length()));

        assertThat(outageOutput)
                .doesNotContain(
                        "CannotGetJdbcConnectionException",
                        "SQLTransientConnectionException",
                        "PSQLException",
                        "ConnectException",
                        "Connection refused",
                        "Failed to validate connection",
                        "PgConnection",
                        "jdbc:postgresql://",
                        "\tat ");
    }
}
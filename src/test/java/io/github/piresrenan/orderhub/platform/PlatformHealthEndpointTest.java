package io.github.piresrenan.orderhub.platform;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.Test;

import org.springframework.context.annotation.Import;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.web.servlet.MockMvc;

import io.github.piresrenan.orderhub.support.PostgreSqlTestConfiguration;

/**
 * Verifies the operational health contract exposed to container orchestrators.
 *
 * <p>These tests intentionally verify only the stable platform contract rather
 * than Spring Boot internal health implementation details.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgreSqlTestConfiguration.class)
class PlatformHealthEndpointTest {

    @Autowired
    private MockMvc mockMvc;

    /**
     * Verifies that the process-level liveness endpoint is available on the
     * application HTTP connector.
     *
     * <p>Why: Kubernetes requires a low-cost signal to determine whether this
     * process should be restarted.</p>
     *
     * <p>Covers: the additional liveness path exposed on the main server.</p>
     *
     * <p>Prevents: deployments depending on business endpoints or a separate
     * management connector to determine JVM liveness.</p>
     */
    @Test
    void exposesLivenessOnMainApplicationServer() throws Exception {

        mockMvc.perform(get("/livez"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
    }

    /**
     * Verifies that the traffic-eligibility endpoint is available on the
     * application HTTP connector.
     *
     * <p>Why: an orchestrator must be able to remove a running but temporarily
     * unavailable replica from service without necessarily restarting it.</p>
     *
     * <p>Covers: the additional readiness path exposed on the main server.</p>
     *
     * <p>Prevents: conflating process survival with eligibility to receive new
     * application traffic.</p>
     */
    @Test
    void exposesReadinessOnMainApplicationServer() throws Exception {

        mockMvc.perform(get("/readyz"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
    }

    /**
     * Verifies that the generic health endpoint remains intentionally minimal.
     *
     * <p>Why: operational endpoints should reveal only the information required
     * for their consumer unless additional disclosure has an explicit purpose.</p>
     *
     * <p>Covers: health-detail suppression.</p>
     *
     * <p>Prevents: accidental disclosure of internal infrastructure topology,
     * component names or future dependency state.</p>
     */
    @Test
    void doesNotExposeHealthComponentDetails() throws Exception {

        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("UP"))
                .andExpect(jsonPath("$.components").doesNotExist());
    }
}
package io.github.piresrenan.orderhub.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.not;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;

import io.github.piresrenan.orderhub.security.adapter.in.authentication.AuthenticatedUserAuthenticationToken;
import io.github.piresrenan.orderhub.security.application.model.AuthenticatedUserPrincipal;
import io.github.piresrenan.orderhub.security.application.model.TrustedTenantContext;
import io.github.piresrenan.orderhub.security.application.port.in.ResolveTrustedTenantContextQuery;
import io.github.piresrenan.orderhub.security.application.port.in.ResolveTrustedTenantContextUseCase;
import io.github.piresrenan.orderhub.support.PostgreSqlTestConfiguration;

/**
 * Verifies the external HTTP contract when durable order persistence becomes
 * unavailable during request processing.
 *
 * <p>
 * The test uses a real PostgreSQL instance and removes that dependency only
 * after the Spring application has started successfully. This distinguishes a
 * runtime persistence outage from an application bootstrap failure.
 * </p>
 */
@SpringBootTest(properties = {
        "spring.datasource.hikari.connection-timeout=1000"
})
@AutoConfigureMockMvc
@Import(PostgreSqlTestConfiguration.class)
@DirtiesContext(classMode = DirtiesContext.ClassMode.AFTER_CLASS)
@ExtendWith(OutputCaptureExtension.class)
class CreateOrderDatabaseFailureIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private PostgreSQLContainer postgresContainer;

    @MockitoBean
    private ResolveTrustedTenantContextUseCase resolveTrustedTenantContextUseCase;

    /**
     * Verifies that a PostgreSQL outage during order creation produces a stable
     * privacy-safe internal error instead of leaking infrastructure details.
     *
     * @param output captured application output used to verify outage log
     *               sanitization
     * @throws Exception when MockMvc cannot execute the request
     */
    @Test
    void returnsSanitizedInternalProblemWhenPostgreSqlFailsDuringCreation(
            CapturedOutput output)
            throws Exception {

        // Why: PostgreSQL can become unavailable after the replica has already
        // accepted traffic, so request-time database failure is a real production
        // path rather than a bootstrap-only concern.
        // Covers: authenticated HTTP -> trusted Tenant boundary -> application ->
        // PostgreSQL repository failure -> HTTP error boundary, including response
        // and log sanitization. JWT and membership resolution are covered separately.
        // Prevents: JDBC/SQL/vendor details, request or security identifiers and stack
        // traces from escaping through the public API or normal outage logs.

        var authenticatedUserId =
                UUID.randomUUID();

        var tenantId =
                UUID.randomUUID();

        var customerId =
                UUID.randomUUID();

        var variantId =
                UUID.randomUUID();

        var authenticatedPrincipal =
                new AuthenticatedUserPrincipal(
                        authenticatedUserId);

        var requestAuthentication =
                new AuthenticatedUserAuthenticationToken(
                        authenticatedPrincipal);

        when(resolveTrustedTenantContextUseCase.resolve(
                new ResolveTrustedTenantContextQuery(
                        authenticatedPrincipal,
                        tenantId)))
                .thenReturn(
                        Optional.of(
                                new TrustedTenantContext(
                                        tenantId)));

        var requestBody = """
                {
                  "customerId": "%s",
                  "items": [
                    {
                      "variantId": "%s",
                      "quantity": 2
                    }
                  ]
                }
                """.formatted(customerId, variantId);

        var outageLogStart =
                output.getAll().length();

        postgresContainer.stop();

        mockMvc.perform(
                        post("/orders")
                                .with(
                                        authentication(
                                                requestAuthentication))
                                .header(
                                        "X-Tenant-Id",
                                        tenantId)
                                .contentType(
                                        MediaType.APPLICATION_JSON)
                                .content(
                                        requestBody))
                .andExpect(
                        status().isInternalServerError())
                .andExpect(
                        content().contentTypeCompatibleWith(
                                MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(
                        jsonPath("$.type")
                                .value(
                                        "urn:orderhub:problem:internal-error"))
                .andExpect(
                        jsonPath("$.title")
                                .value(
                                        "Internal server error"))
                .andExpect(
                        jsonPath("$.detail")
                                .value(
                                        "The request could not be completed."))
                .andExpect(
                        jsonPath("$.code")
                                .value(
                                        "INTERNAL_ERROR"))
                .andExpect(
                        content().string(
                                not(containsString(
                                        authenticatedUserId.toString()))))
                .andExpect(
                        content().string(
                                not(containsString(
                                        tenantId.toString()))))
                .andExpect(
                        content().string(
                                not(containsString(
                                        customerId.toString()))))
                .andExpect(
                        content().string(
                                not(containsString(
                                        variantId.toString()))))
                .andExpect(
                        content().string(
                                not(containsString(
                                        "jdbc:postgresql://"))))
                .andExpect(
                        content().string(
                                not(containsString(
                                        "PSQLException"))))
                .andExpect(
                        content().string(
                                not(containsString(
                                        "SQLException"))));

        var completeOutput =
                output.getAll();

        var outageOutput =
                completeOutput.substring(
                        Math.min(
                                outageLogStart,
                                completeOutput.length()));

        assertThat(outageOutput)
                .doesNotContain(
                        authenticatedUserId.toString(),
                        tenantId.toString(),
                        customerId.toString(),
                        variantId.toString(),
                        "CannotGetJdbcConnectionException",
                        "DataAccessResourceFailureException",
                        "SQLTransientConnectionException",
                        "PSQLException",
                        "SQLException",
                        "ConnectException",
                        "Connection refused",
                        "PgConnection",
                        "jdbc:postgresql://",
                        "\\tat ");
    }
}

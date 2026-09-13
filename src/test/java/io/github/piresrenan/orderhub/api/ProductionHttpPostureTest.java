package io.github.piresrenan.orderhub.api;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import io.github.piresrenan.orderhub.support.PostgreSqlTestConfiguration;

/** Why: production defaults must be safe; Covers: actual filters and mappings; Prevents: docs/auth disclosure. */
@SpringBootTest
@ActiveProfiles("production")
@AutoConfigureMockMvc
@Import(PostgreSqlTestConfiguration.class)
class ProductionHttpPostureTest {
    @Autowired MockMvc mvc;
    @Autowired org.springframework.web.context.WebApplicationContext context;

    @BeforeEach
    void installSecurityTestContextSupport() {
        mvc = org.springframework.test.web.servlet.setup.MockMvcBuilders.webAppContextSetup(context)
                .apply(org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity()).build();
    }

    @Test
    @WithMockUser
    void runtimeDocumentationIsAbsentEvenForAuthenticatedUsers() throws Exception {
        for (var path : new String[]{"/v3/api-docs", "/v3/api-docs.yaml", "/swagger-ui.html", "/swagger-ui/index.html"}) {
            mvc.perform(get(path)).andExpect(status().isNotFound());
        }
    }

    @Test
    void authenticationFailuresAreBoundedProblemsAndNeverCopyBearerInput() throws Exception {
        for (var path : new String[]{"/orders", "/identity/bootstrap/staff", "/catalog/products"}) {
            var response = mvc.perform(get(path).header("Authorization", "Bearer synthetic-private-token-not-a-jwt"))
                    .andExpect(status().isUnauthorized())
                    .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                    .andExpect(header().string("WWW-Authenticate", "Bearer"))
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(jsonPath("$.status").value(401)).andReturn().getResponse();
            assertThat(response.getContentAsString()).doesNotContain("synthetic-private", "Exception", "jwt", "issuer", "subject");
        }
    }

    @Test
    void deniedTenantContextHasAProblemBodyWithoutPrivateSelectors() throws Exception {
        var user = java.util.UUID.randomUUID();
        var tenant = java.util.UUID.randomUUID();
        var authentication = new io.github.piresrenan.orderhub.security.adapter.in.authentication.AuthenticatedUserAuthenticationToken(
                new io.github.piresrenan.orderhub.security.application.model.AuthenticatedUserPrincipal(user));
        var response = mvc.perform(get("/orders/" + java.util.UUID.randomUUID())
                        .with(org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication(authentication))
                        .header("X-Tenant-Id", tenant))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.status").value(403)).andReturn().getResponse();
        assertThat(response.getContentAsString()).doesNotContain(user.toString(), tenant.toString(), "Exception");
    }

    @Test
    @WithMockUser
    void managementIsLimitedToMinimalHealth() throws Exception {
        for (var path : new String[]{"/actuator/env", "/actuator/beans", "/actuator/configprops", "/actuator/heapdump", "/actuator/mappings", "/actuator/metrics"}) {
            mvc.perform(get(path)).andExpect(status().isNotFound());
        }
        mvc.perform(get("/actuator/health")).andExpect(status().isOk()).andExpect(jsonPath("$.components").doesNotExist());
    }
}

package io.github.piresrenan.orderhub.security.adapter.in.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.web.method.annotation.AuthenticationPrincipalArgumentResolver;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;

import io.github.piresrenan.orderhub.security.application.model.AuthenticatedUserPrincipal;
import io.github.piresrenan.orderhub.security.application.port.in.DiscoverSelectableTenantsUseCase;
import io.github.piresrenan.orderhub.security.application.port.in.SelectableTenantPage;
import io.github.piresrenan.orderhub.tenants.application.port.in.operational.TenantOperationalStateUnavailableException;
import io.github.piresrenan.orderhub.users.application.port.in.TenantMembershipScanUnavailableException;

/**
 * Why: discovery must fail closed; a technical failure must never look like an empty or partial Tenant set.
 * Scenario: the Users scan or the Tenants batch is unavailable, or an unexpected internal error occurs.
 * Covers: TenantDiscoveryExceptionHandler mapping and the controller's cache header and parameter bounds.
 * Expected: sanitized 500 Problem Details without Tenant data or causes; valid pages carry no-store.
 * Prevents: fail-open discovery, diagnostic leakage and cacheable discovery responses.
 */
class TenantDiscoveryControllerFailureTest {
    private static final String SECRET_CAUSE = "synthetic-private-cause-detail";
    private final UUID user = UUID.randomUUID();

    @AfterEach void clear() { SecurityContextHolder.clearContext(); }

    @Test void ownerUnavailabilityAndUnexpectedErrorsBecomeSanitizedTechnicalFailures() throws Exception {
        for (RuntimeException failure : List.of(
                new TenantMembershipScanUnavailableException(new IllegalStateException(SECRET_CAUSE)),
                new TenantOperationalStateUnavailableException(new IllegalStateException(SECRET_CAUSE)),
                new IllegalStateException(SECRET_CAUSE),
                new IllegalArgumentException(SECRET_CAUSE))) {
            var body = mvc(query -> { throw failure; }).perform(get("/tenants"))
                    .andExpect(status().isInternalServerError())
                    .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                    .andExpect(jsonPath("$.code").value("tenant-discovery-technical-failure"))
                    .andExpect(jsonPath("$.items").doesNotExist())
                    .andReturn().getResponse().getContentAsString();
            assertThat(body).doesNotContain(SECRET_CAUSE, user.toString(), "Exception");
        }
    }

    @Test void successfulPageIsNeverCacheable() throws Exception {
        mvc(query -> new SelectableTenantPage(List.of(), null)).perform(get("/tenants?limit=1"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
    }

    private MockMvc mvc(DiscoverSelectableTenantsUseCase discovery) {
        SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken(new AuthenticatedUserPrincipal(user), null));
        return MockMvcBuilders.standaloneSetup(new TenantDiscoveryController(discovery))
                .setControllerAdvice(new TenantDiscoveryExceptionHandler())
                .setCustomArgumentResolvers(new AuthenticationPrincipalArgumentResolver())
                .build();
    }
}

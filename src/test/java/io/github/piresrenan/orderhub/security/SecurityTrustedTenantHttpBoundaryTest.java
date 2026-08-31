package io.github.piresrenan.orderhub.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import io.github.piresrenan.orderhub.security.application.model.AuthenticatedUserPrincipal;
import io.github.piresrenan.orderhub.security.application.model.TrustedTenantContext;
import io.github.piresrenan.orderhub.security.application.port.in.ResolveTrustedTenantContextQuery;
import io.github.piresrenan.orderhub.security.application.port.in.ResolveTrustedTenantContextUseCase;
import io.github.piresrenan.orderhub.support.PostgreSqlTestConfiguration;
import io.github.piresrenan.orderhub.users.application.port.in.ResolveExternalIdentityUseCase;
import io.github.piresrenan.orderhub.users.application.port.in.ResolvedUserIdentity;

@SpringBootTest(properties = {
        "orderhub.security.jwt.issuer=https://issuer.example.test",
        "orderhub.security.jwt.audience=orderhub-api",
        "orderhub.security.jwt.jwk-set-uri=http://127.0.0.1:1/test-only-jwks"
})
@AutoConfigureMockMvc
@Import({
        PostgreSqlTestConfiguration.class,
        SecurityTrustedTenantHttpBoundaryTest.ProbeController.class
})
class SecurityTrustedTenantHttpBoundaryTest {

    private static final String ISSUER =
            "https://issuer.example.test";

    private static final String SUBJECT =
            "synthetic-subject-tenant-http";

    private static final String ACCESS_TOKEN =
            "synthetic-tenant-http-token";

    private static final String TENANT_HEADER =
            "X-Tenant-Id";

    private static final String PROBE_PATH =
            "/security-test/trusted-tenant";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private ResolveExternalIdentityUseCase externalIdentities;

    @MockitoBean
    private ResolveTrustedTenantContextUseCase trustedTenants;

    @Test
    void resolvesTrustedTenantContextFromMappedBearerAndRequestedTenant()
            throws Exception {

        // Why: the HTTP boundary must combine the authenticated internal User
        // with the untrusted Tenant selector before a controller can receive
        // Tenant authority.
        // Covers: bearer -> internal principal -> X-Tenant-Id -> trusted context.
        // Prevents: controller access to raw caller-selected Tenant authority.

        var userId =
                UUID.randomUUID();

        var tenantId =
                UUID.randomUUID();

        stubAuthenticatedUser(
                userId);

        var query =
                new ResolveTrustedTenantContextQuery(
                        new AuthenticatedUserPrincipal(
                                userId),
                        tenantId);

        when(trustedTenants.resolve(
                query))
                .thenReturn(
                        Optional.of(
                                new TrustedTenantContext(
                                        tenantId)));

        mockMvc.perform(
                        get(PROBE_PATH)
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        "Bearer " + ACCESS_TOKEN)
                                .header(
                                        TENANT_HEADER,
                                        tenantId))
                .andExpect(
                        status().isOk())
                .andExpect(
                        content().string(
                                tenantId.toString()));

        verify(trustedTenants)
                .resolve(
                        query);
    }

    @Test
    void rejectsMissingTenantSelectorBeforeTenantResolution()
            throws Exception {

        // Why: an absent Tenant selector is malformed request metadata rather
        // than an authorization decision.
        // Covers: authenticated request without X-Tenant-Id.
        // Prevents: unscoped Tenant access or unnecessary membership lookup.

        stubAuthenticatedUser(
                UUID.randomUUID());

        mockMvc.perform(
                        get(PROBE_PATH)
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        "Bearer " + ACCESS_TOKEN))
                .andExpect(
                        status().isBadRequest());

        verifyNoInteractions(
                trustedTenants);
    }

    @Test
    void rejectsMalformedTenantSelectorBeforeTenantResolution()
            throws Exception {

        // Why: malformed Tenant identifiers must stop at the HTTP syntax
        // boundary.
        // Covers: authenticated request with non-UUID X-Tenant-Id.
        // Prevents: malformed input being misreported as authorization denial.

        stubAuthenticatedUser(
                UUID.randomUUID());

        mockMvc.perform(
                        get(PROBE_PATH)
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        "Bearer " + ACCESS_TOKEN)
                                .header(
                                        TENANT_HEADER,
                                        "not-a-uuid"))
                .andExpect(
                        status().isBadRequest());

        verifyNoInteractions(
                trustedTenants);
    }

    @Test
    void deniesAuthenticatedUserWithoutTenantMembershipWithoutLeakingIds()
            throws Exception {

        // Why: successful authentication does not authorize an arbitrary Tenant.
        // Covers: valid internal principal and Tenant selector with absent
        // trusted Tenant resolution.
        // Prevents: forged Tenant selectors crossing authorization boundaries.

        var userId =
                UUID.randomUUID();

        var tenantId =
                UUID.randomUUID();

        stubAuthenticatedUser(
                userId);

        var query =
                new ResolveTrustedTenantContextQuery(
                        new AuthenticatedUserPrincipal(
                                userId),
                        tenantId);

        when(trustedTenants.resolve(
                query))
                .thenReturn(
                        Optional.empty());

        var result =
                mockMvc.perform(
                                get(PROBE_PATH)
                                        .header(
                                                HttpHeaders.AUTHORIZATION,
                                                "Bearer " + ACCESS_TOKEN)
                                        .header(
                                                TENANT_HEADER,
                                                tenantId))
                        .andExpect(
                                status().isForbidden())
                        .andReturn();

        assertThat(
                result
                        .getResponse()
                        .getContentAsString())
                .doesNotContain(
                        userId.toString(),
                        tenantId.toString());

        verify(trustedTenants)
                .resolve(
                        query);
    }

    @Test
    void changingOnlyTenantSelectorCannotCrossTenantBoundary()
            throws Exception {

        // Why: possessing a valid bearer token for one User must not make every
        // caller-selected Tenant authoritative.
        // Covers: identical authentication with one allowed and one denied
        // Tenant selector.
        // Prevents: X-Tenant-Id tampering becoming cross-Tenant access.

        var userId =
                UUID.randomUUID();

        var allowedTenantId =
                UUID.randomUUID();

        var deniedTenantId =
                UUID.randomUUID();

        stubAuthenticatedUser(
                userId);

        var principal =
                new AuthenticatedUserPrincipal(
                        userId);

        var allowedQuery =
                new ResolveTrustedTenantContextQuery(
                        principal,
                        allowedTenantId);

        var deniedQuery =
                new ResolveTrustedTenantContextQuery(
                        principal,
                        deniedTenantId);

        when(trustedTenants.resolve(
                allowedQuery))
                .thenReturn(
                        Optional.of(
                                new TrustedTenantContext(
                                        allowedTenantId)));

        when(trustedTenants.resolve(
                deniedQuery))
                .thenReturn(
                        Optional.empty());

        mockMvc.perform(
                        get(PROBE_PATH)
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        "Bearer " + ACCESS_TOKEN)
                                .header(
                                        TENANT_HEADER,
                                        allowedTenantId))
                .andExpect(
                        status().isOk())
                .andExpect(
                        content().string(
                                allowedTenantId.toString()));

        mockMvc.perform(
                        get(PROBE_PATH)
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        "Bearer " + ACCESS_TOKEN)
                                .header(
                                        TENANT_HEADER,
                                        deniedTenantId))
                .andExpect(
                        status().isForbidden());

        verify(trustedTenants)
                .resolve(
                        allowedQuery);

        verify(trustedTenants)
                .resolve(
                        deniedQuery);
    }

    private void stubAuthenticatedUser(
            UUID userId) {

        when(jwtDecoder.decode(
                ACCESS_TOKEN))
                .thenReturn(
                        jwt());

        when(externalIdentities.resolve(
                any()))
                .thenReturn(
                        Optional.of(
                                new ResolvedUserIdentity(
                                        userId)));
    }

    private Jwt jwt() {
        var now =
                Instant.now();

        return Jwt
                .withTokenValue(
                        ACCESS_TOKEN)
                .header(
                        "alg",
                        "RS256")
                .claim(
                        "iss",
                        ISSUER)
                .claim(
                        "sub",
                        SUBJECT)
                .claim(
                        "aud",
                        "orderhub-api")
                .issuedAt(
                        now.minusSeconds(
                                30))
                .expiresAt(
                        now.plusSeconds(
                                300))
                .build();
    }

    @RestController
    static final class ProbeController {

        @GetMapping(PROBE_PATH)
        String trustedTenant(
                TrustedTenantContext tenantContext) {

            return tenantContext
                    .tenantId()
                    .toString();
        }
    }
}

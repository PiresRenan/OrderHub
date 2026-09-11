package io.github.piresrenan.orderhub.administration.web;

import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.RSAKey;

import io.github.piresrenan.orderhub.security.support.RealJwtTestSupport;
import io.github.piresrenan.orderhub.support.PostgreSqlTestConfiguration;
import io.github.piresrenan.orderhub.users.application.port.in.IsTenantMembershipOperationallyActiveUseCase;
import io.github.piresrenan.orderhub.users.application.port.in.ResolveExternalIdentityQuery;
import io.github.piresrenan.orderhub.users.application.port.in.ResolveExternalIdentityUseCase;
import io.github.piresrenan.orderhub.users.application.port.in.ResolvedUserIdentity;

/**
 * Why: Platform authority must come from current internal grants rather than JWT claims.
 * Covers: Real JWT authentication and the Platform administration boundary.
 * Prevents: Provider-claim escalation and regression from identity schema evolution.
 */
@SpringBootTest(properties = {
        "orderhub.security.jwt.issuer=https://issuer.example.test",
        "orderhub.security.jwt.audience=orderhub-api",
        "orderhub.security.jwt.jwk-set-uri=http://127.0.0.1:1/unused-real-jwt-test-jwks"
})
@AutoConfigureMockMvc
@Import({
        PostgreSqlTestConfiguration.class,
        RealJwtPlatformAdministrationAcceptanceTest.JwtConfiguration.class
})
class RealJwtPlatformAdministrationAcceptanceTest {

    private static final String ISSUER = "https://issuer.example.test";
    private static final String SUBJECT = "platform-administration-subject";
    private static final String AUDIENCE = "orderhub-api";
    private static final RSAKey KEY = RealJwtTestSupport.generateRsaKey("platform-admin-key");
    private static final UUID USER_ID = UUID.randomUUID();

    @Autowired
    private MockMvc mvc;

    @Autowired
    private JdbcTemplate jdbc;

    @MockitoBean
    private ResolveExternalIdentityUseCase externalIdentities;

    @MockitoBean
    private IsTenantMembershipOperationallyActiveUseCase memberships;

    @BeforeEach
    void authenticateKnownInternalUserAndClearAuthority() {
        jdbc.update("DELETE FROM access_control.administrative_grants WHERE user_id = ?", USER_ID);
        when(externalIdentities.resolve(new ResolveExternalIdentityQuery(ISSUER, SUBJECT)))
                .thenReturn(Optional.of(new ResolvedUserIdentity(USER_ID)));
    }

    @Test
    void realJwtClaimsAloneDoNotGrantPlatformAuthority() throws Exception {
        mvc.perform(get("/platform/organizations")
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isForbidden());
    }

    @Test
    void exactPlatformGrantAllowsRouteWithoutTenantSelector() throws Exception {
        jdbc.update("""
                INSERT INTO access_control.administrative_grants (
                    grant_id, user_id, scope_type, scope_id, permission_code)
                VALUES (?, ?, 'PLATFORM', NULL, 'PLATFORM_ORGANIZATIONS_VIEW')
                """, UUID.randomUUID(), USER_ID);

        mvc.perform(get("/platform/organizations")
                        .header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk());
    }

    private static String bearer() throws JOSEException {
        var now = Instant.now();
        return "Bearer " + RealJwtTestSupport.signedToken(
                KEY, ISSUER, SUBJECT, AUDIENCE,
                now.plusSeconds(300), now.minusSeconds(30));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class JwtConfiguration {
        @Bean
        @Primary
        JwtDecoder realJwtDecoder() throws JOSEException {
            return RealJwtTestSupport.decoder(KEY, ISSUER, AUDIENCE);
        }
    }
}

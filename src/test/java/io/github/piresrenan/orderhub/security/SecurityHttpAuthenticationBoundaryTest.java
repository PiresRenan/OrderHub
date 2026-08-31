package io.github.piresrenan.orderhub.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import io.github.piresrenan.orderhub.support.PostgreSqlTestConfiguration;
import io.github.piresrenan.orderhub.users.application.port.in.ResolveExternalIdentityUseCase;
import io.github.piresrenan.orderhub.users.application.port.in.ResolvedUserIdentity;

@SpringBootTest(properties = {
        "orderhub.security.jwt.issuer=https://issuer.example.test",
        "orderhub.security.jwt.audience=orderhub-api",
        "orderhub.security.jwt.jwk-set-uri=http://127.0.0.1:1/test-only-jwks"
})
@AutoConfigureMockMvc
@Import(PostgreSqlTestConfiguration.class)
class SecurityHttpAuthenticationBoundaryTest {

    private static final String ISSUER =
            "https://issuer.example.test";

    private static final String SUBJECT =
            "synthetic-subject-001";

    private static final String ACCESS_TOKEN =
            "synthetic-http-access-token";

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private JwtDecoder jwtDecoder;

    @MockitoBean
    private ResolveExternalIdentityUseCase externalIdentities;

    @Test
    void rejectsProtectedRequestWithoutBearerToken() throws Exception {
        // Why: Orders must never execute for an unauthenticated caller.
        // Covers: protected HTTP request without Authorization bearer input.
        // Prevents: anonymous access reaching the Orders adapter or application
        // boundary.

        mockMvc.perform(
                        get("/orders"))
                .andExpect(
                        status().isUnauthorized());

        verifyNoInteractions(
                jwtDecoder,
                externalIdentities);
    }

    @Test
    void rejectsCryptographicallyAcceptedButUnknownExternalIdentity()
            throws Exception {

        // Why: a valid JWT proves possession of a trusted external identity but
        // does not create an internal OrderHub User binding.
        // Covers: validated JWT -> external identity resolution -> generic 401.
        // Prevents: unmapped identity authenticating merely because JWT
        // cryptography succeeded.

        when(jwtDecoder.decode(
                ACCESS_TOKEN))
                .thenReturn(
                        jwt());

        when(externalIdentities.resolve(
                any()))
                .thenReturn(
                        Optional.empty());

        var result =
                mockMvc.perform(
                                get("/orders")
                                        .header(
                                                HttpHeaders.AUTHORIZATION,
                                                "Bearer " + ACCESS_TOKEN))
                        .andExpect(
                                status().isUnauthorized())
                        .andReturn();

        var responseBody =
                result
                        .getResponse()
                        .getContentAsString();

        assertThat(responseBody)
                .doesNotContain(
                        ISSUER,
                        SUBJECT);

        verify(externalIdentities)
                .resolve(
                        argThat(query ->
                                ISSUER.equals(
                                        query.issuer())
                                        && SUBJECT.equals(
                                                query.subject())));
    }

    @Test
    void mappedExternalIdentityPassesAuthenticationBoundary()
            throws Exception {

        // Why: a validated JWT mapped to an internal User must be allowed past
        // authentication so request-level validation can continue.
        // Covers: Resource Server use of OrderHub's custom JWT converter.
        // Prevents: Spring's default JWT principal remaining the effective
        // application authentication identity.

        var userId =
                UUID.randomUUID();

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

        mockMvc.perform(
                        get("/orders")
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        "Bearer " + ACCESS_TOKEN))
                .andExpect(
                        status().isMethodNotAllowed());

        verify(externalIdentities)
                .resolve(
                        argThat(query ->
                                ISSUER.equals(
                                        query.issuer())
                                        && SUBJECT.equals(
                                                query.subject())));
    }

    @Test
    void doesNotCreateSecuritySessionForUnauthenticatedRequest()
            throws Exception {

        // Why: bearer authentication is stateless and must not persist failed
        // request state for replay after a future authentication mechanism.
        // Covers: absence of HttpSession creation for a protected anonymous
        // request.
        // Prevents: saved-request/session behavior silently turning the API into
        // a stateful authentication boundary.

        var result =
                mockMvc.perform(
                                get("/orders"))
                        .andExpect(
                                status().isUnauthorized())
                        .andReturn();

        assertThat(
                result
                        .getRequest()
                        .getSession(
                                false))
                .isNull();

        verifyNoInteractions(
                jwtDecoder,
                externalIdentities);
    }

    @Test
    void authenticatedBearerPostDoesNotRequireCsrfToken()
            throws Exception {

        // Why: OH-010 authenticates the API exclusively with bearer credentials
        // supplied explicitly through the Authorization header.
        // Covers: POST request continuation after bearer authentication without a
        // CSRF token.
        // Prevents: CSRF protection intended for cookie-authenticated sessions
        // blocking the stateless Resource Server API.

        var userId =
                UUID.randomUUID();

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

        mockMvc.perform(
                        post("/orders")
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        "Bearer " + ACCESS_TOKEN)
                                .contentType(
                                        MediaType.APPLICATION_JSON)
                                .content(
                                        "{}"))
                .andExpect(
                        status().isBadRequest());

        verify(externalIdentities)
                .resolve(
                        argThat(query ->
                                ISSUER.equals(
                                        query.issuer())
                                        && SUBJECT.equals(
                                                query.subject())));
    }

    @Test
    void livenessRemainsPublic() throws Exception {
        // Why: Kubernetes must evaluate process liveness without obtaining an
        // application access token.
        // Covers: existing /livez operational contract through Security.
        // Prevents: authentication configuration causing healthy Pods to restart.

        mockMvc.perform(
                        get("/livez"))
                .andExpect(
                        status().isOk());

        verifyNoInteractions(
                jwtDecoder,
                externalIdentities);
    }

    @Test
    void readinessRemainsPublic() throws Exception {
        // Why: Kubernetes must evaluate traffic eligibility independently from
        // interactive authentication.
        // Covers: existing /readyz operational contract through Security.
        // Prevents: authenticated-only readiness making every replica unready.

        mockMvc.perform(
                        get("/readyz"))
                .andExpect(
                        status().isOk());

        verifyNoInteractions(
                jwtDecoder,
                externalIdentities);
    }

    @Test
    void genericHealthRemainsPublic() throws Exception {
        // Why: the existing platform health contract is intentionally exposed
        // for operational inspection without bearer authentication.
        // Covers: /actuator/health after Resource Server activation.
        // Prevents: security configuration silently breaking existing health
        // monitoring and smoke checks.

        mockMvc.perform(
                        get("/actuator/health"))
                .andExpect(
                        status().isOk());

        verifyNoInteractions(
                jwtDecoder,
                externalIdentities);
    }

    /**
     * Creates one synthetic already-decoded JWT for HTTP filter-chain tests.
     *
     * <p>Cryptographic verification is intentionally outside this fixture because
     * JwtDecoder composition and validation policy already have independent
     * synthetic-RSA integration coverage.
     *
     * @return synthetic validated JWT
     */
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
}

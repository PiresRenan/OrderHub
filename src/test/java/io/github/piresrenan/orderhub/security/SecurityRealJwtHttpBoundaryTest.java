package io.github.piresrenan.orderhub.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.RSAKey;

import io.github.piresrenan.orderhub.security.support.RealJwtTestSupport;
import io.github.piresrenan.orderhub.support.PostgreSqlTestConfiguration;
import io.github.piresrenan.orderhub.users.application.port.in.FindTenantMembershipUseCase;
import io.github.piresrenan.orderhub.users.application.port.in.ResolveExternalIdentityQuery;
import io.github.piresrenan.orderhub.users.application.port.in.ResolveExternalIdentityUseCase;
import io.github.piresrenan.orderhub.users.application.port.in.ResolvedUserIdentity;

@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(properties = {
        "orderhub.security.jwt.issuer=https://issuer.example.test",
        "orderhub.security.jwt.audience=orderhub-api",
        "orderhub.security.jwt.jwk-set-uri=http://127.0.0.1:1/unused-real-jwt-test-jwks"
})
@AutoConfigureMockMvc
@Import({
        PostgreSqlTestConfiguration.class,
        SecurityRealJwtHttpBoundaryTest.RealJwtTestConfiguration.class
})
class SecurityRealJwtHttpBoundaryTest {

    private static final String ISSUER =
            "https://issuer.example.test";

    private static final String AUDIENCE =
            "orderhub-api";

    private static final String SUBJECT =
            "synthetic-real-jwt-subject";

    private static final RSAKey SIGNING_KEY =
            generateRsaKey(
                    "orderhub-real-jwt-test-key");

    private static final RSAKey UNTRUSTED_SIGNING_KEY =
            generateRsaKey(
                    "orderhub-untrusted-test-key");

    @Autowired
    private MockMvc mockMvc;

    @MockitoBean
    private ResolveExternalIdentityUseCase externalIdentities;

    @MockitoBean
    private FindTenantMembershipUseCase memberships;

    @Test
    void acceptsRealRs256JwtAndReachesApplicationAuthenticationBoundary()
            throws Exception {

        // Why: mocked JwtDecoder tests cannot prove that Resource Server really
        // parses and cryptographically verifies a compact signed JWT.
        // Covers: Bearer extraction -> Nimbus RS256 verification -> validation
        // policy -> issuer/subject mapping -> authenticated MVC request.
        // Prevents: a configuration that works only when JwtDecoder is mocked.

        var userId =
                UUID.randomUUID();

        var identityQuery =
                new ResolveExternalIdentityQuery(
                        ISSUER,
                        SUBJECT);

        when(externalIdentities.resolve(
                identityQuery))
                .thenReturn(
                        Optional.of(
                                new ResolvedUserIdentity(
                                        userId)));

        mockMvc.perform(
                        get("/orders")
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(
                                                validToken())))
                .andExpect(
                        status().isMethodNotAllowed());

        verify(externalIdentities)
                .resolve(
                        identityQuery);

        verifyNoInteractions(
                memberships);
    }

    @Test
    void rejectsRealJwtSignedByUntrustedPrivateKeyBeforeIdentityResolution()
            throws Exception {

        // Why: possession of a structurally valid JWT must not bypass signature
        // trust.
        // Covers: actual RS256 signature verification using a different key.
        // Prevents: accepting forged tokens merely because claims are well formed.

        var now =
                Instant.now();

        var token =
                signedToken(
                        UNTRUSTED_SIGNING_KEY,
                        ISSUER,
                        AUDIENCE,
                        now.plusSeconds(
                                300),
                        now.minusSeconds(
                                30));

        mockMvc.perform(
                        get("/orders")
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(
                                                token)))
                .andExpect(
                        status().isUnauthorized());

        verifyNoInteractions(
                externalIdentities,
                memberships);
    }

    @Test
    void rejectsExpiredRealJwtBeforeIdentityResolution()
            throws Exception {

        // Why: a correctly signed token must cease to authenticate after its
        // validity window.
        // Covers: Resource Server temporal expiration validation.
        // Prevents: replay of cryptographically valid but expired credentials.

        var now =
                Instant.now();

        var token =
                signedToken(
                        SIGNING_KEY,
                        ISSUER,
                        AUDIENCE,
                        now.minusSeconds(
                                300),
                        now.minusSeconds(
                                600));

        mockMvc.perform(
                        get("/orders")
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(
                                                token)))
                .andExpect(
                        status().isUnauthorized());

        verifyNoInteractions(
                externalIdentities,
                memberships);
    }

    @Test
    void rejectsRealJwtThatIsNotYetValidBeforeIdentityResolution()
            throws Exception {

        // Why: a signed credential cannot authenticate before its nbf boundary.
        // Covers: Resource Server not-before validation with a value safely beyond
        // normal clock-skew tolerance.
        // Prevents: premature use of future credentials.

        var now =
                Instant.now();

        var token =
                signedToken(
                        SIGNING_KEY,
                        ISSUER,
                        AUDIENCE,
                        now.plusSeconds(
                                900),
                        now.plusSeconds(
                                300));

        mockMvc.perform(
                        get("/orders")
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(
                                                token)))
                .andExpect(
                        status().isUnauthorized());

        verifyNoInteractions(
                externalIdentities,
                memberships);
    }

    @Test
    void rejectsRealJwtFromUnexpectedIssuerBeforeIdentityResolution()
            throws Exception {

        // Why: a trusted signing key alone must not make another issuer trusted.
        // Covers: production issuer policy in the real decoder path.
        // Prevents: cross-issuer token acceptance.

        var now =
                Instant.now();

        var token =
                signedToken(
                        SIGNING_KEY,
                        "https://untrusted-issuer.example.test",
                        AUDIENCE,
                        now.plusSeconds(
                                300),
                        now.minusSeconds(
                                30));

        mockMvc.perform(
                        get("/orders")
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(
                                                token)))
                .andExpect(
                        status().isUnauthorized());

        verifyNoInteractions(
                externalIdentities,
                memberships);
    }

    @Test
    void rejectsRealJwtForUnexpectedAudienceBeforeIdentityResolution()
            throws Exception {

        // Why: a valid token issued for another resource must not authenticate
        // against OrderHub.
        // Covers: expected-audience validation in the real HTTP decoder path.
        // Prevents: bearer token substitution across resource servers.

        var now =
                Instant.now();

        var token =
                signedToken(
                        SIGNING_KEY,
                        ISSUER,
                        "another-api",
                        now.plusSeconds(
                                300),
                        now.minusSeconds(
                                30));

        mockMvc.perform(
                        get("/orders")
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(
                                                token)))
                .andExpect(
                        status().isUnauthorized());

        verifyNoInteractions(
                externalIdentities,
                memberships);
    }

    @Test
    void rejectsRealJwtWithBlankSubjectAsUnauthorizedBeforeIdentityResolution()
            throws Exception {

        // Why: a correctly signed token may contain a present but whitespace-only
        // subject that still cannot identify an OrderHub User.
        // Covers: real JWT decoding followed by structural subject rejection at
        // the authentication adapter boundary.
        // Prevents: application query validation escaping as a 500 response
        // instead of the stable privacy-safe 401 authentication failure.

        var now =
                Instant.now();

        var token =
                RealJwtTestSupport.signedToken(
                        SIGNING_KEY,
                        ISSUER,
                        "   ",
                        AUDIENCE,
                        now.plusSeconds(
                                300),
                        now.minusSeconds(
                                30));

        mockMvc.perform(
                        get("/orders")
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(
                                                token)))
                .andExpect(
                        status().isUnauthorized());

        verifyNoInteractions(
                externalIdentities,
                memberships);
    }

    @Test
    void rejectsValidRealJwtWithUnknownIdentityWithoutEnumeratingClaims(
            CapturedOutput output)
            throws Exception {

        // Why: cryptographic validity is insufficient when the external identity
        // is not bound to an internal OrderHub User.
        // Covers: real decoder success followed by generic identity-mapping
        // authentication failure.
        // Prevents: account enumeration through issuer, subject or token echo.

        var token =
                validToken();

        var response =
                mockMvc.perform(
                                get("/orders")
                                        .header(
                                                HttpHeaders.AUTHORIZATION,
                                                bearer(
                                                        token)))
                        .andExpect(
                                status().isUnauthorized())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertThat(response)
                .doesNotContain(
                        token,
                        ISSUER,
                        SUBJECT);

        assertThat(output.getAll())
                .doesNotContain(
                        token,
                        ISSUER,
                        SUBJECT);

        verify(externalIdentities)
                .resolve(
                        new ResolveExternalIdentityQuery(
                                ISSUER,
                                SUBJECT));

        verifyNoInteractions(
                memberships);
    }

    private static String validToken()
            throws JOSEException {

        var now =
                Instant.now();

        return signedToken(
                SIGNING_KEY,
                ISSUER,
                AUDIENCE,
                now.plusSeconds(
                        300),
                now.minusSeconds(
                        30));
    }

    private static String signedToken(
            RSAKey signingKey,
            String issuer,
            String audience,
            Instant expiresAt,
            Instant notBefore)
            throws JOSEException {

        return RealJwtTestSupport.signedToken(
                signingKey,
                issuer,
                SUBJECT,
                audience,
                expiresAt,
                notBefore);
    }

    private static String bearer(
            String token) {

        return "Bearer " + token;
    }

    private static RSAKey generateRsaKey(
            String keyId) {

        return RealJwtTestSupport.generateRsaKey(
                keyId);
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RealJwtTestConfiguration {

        /**
         * Supplies a real Nimbus decoder backed by synthetic test cryptography.
         *
         * <p>The production validation policy is retained while JWK discovery is
         * deliberately replaced with the in-memory public key. No network,
         * external OIDC provider or mocked JwtDecoder participates in this proof.
         *
         * @return real decoder for signed synthetic access tokens
         * @throws JOSEException when the synthetic RSA public key cannot be built
         */
        @Bean
        @Primary
        JwtDecoder realJwtDecoder()
                throws JOSEException {

            return RealJwtTestSupport.decoder(
                    SIGNING_KEY,
                    ISSUER,
                    AUDIENCE);
        }
    }
}

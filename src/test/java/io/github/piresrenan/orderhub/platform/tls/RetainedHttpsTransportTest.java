package io.github.piresrenan.orderhub.platform.tls;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

import java.io.IOException;
import java.io.UncheckedIOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import javax.net.ssl.SSLHandshakeException;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;

import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.util.FileSystemUtils;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.RSAKey;

import io.github.piresrenan.orderhub.security.support.RealJwtTestSupport;
import io.github.piresrenan.orderhub.support.PostgreSqlTestConfiguration;
import io.github.piresrenan.orderhub.users.application.port.in.IsTenantMembershipOperationallyActiveUseCase;
import io.github.piresrenan.orderhub.users.application.port.in.ResolveExternalIdentityQuery;
import io.github.piresrenan.orderhub.users.application.port.in.ResolveExternalIdentityUseCase;
import io.github.piresrenan.orderhub.users.application.port.in.ResolvedUserIdentity;

/**
 * Qualifies the retained HTTPS transport boundary (OH-027, ADR-0023) on the
 * real embedded Tomcat listener using externally supplied synthetic PEM
 * material and real JSSE clients.
 */
@ExtendWith(OutputCaptureExtension.class)
@SpringBootTest(
        webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {
                "orderhub.security.jwt.token-profile=GENERIC",
                "orderhub.security.jwt.issuer=https://issuer.example.test",
                "orderhub.security.jwt.audience=orderhub-api",
                "orderhub.security.jwt.jwk-set-uri=http://127.0.0.1:1/unused-tls-test-jwks"
        })
@ActiveProfiles("retained")
@Import({
        PostgreSqlTestConfiguration.class,
        RetainedHttpsTransportTest.RealJwtConfiguration.class
})
class RetainedHttpsTransportTest {

    static final String SERVICE_IDENTITY = "orderhub.orderhub-test.svc";

    private static final String ISSUER = "https://issuer.example.test";
    private static final String AUDIENCE = "orderhub-api";
    private static final String SUBJECT = "synthetic-tls-subject";

    private static final RSAKey SIGNING_KEY =
            RealJwtTestSupport.generateRsaKey("orderhub-tls-test-key");

    private static final Path ROOT;
    private static final SyntheticTlsMaterial SERVER_MATERIAL;
    private static final SyntheticTlsMaterial FOREIGN_MATERIAL;

    static {
        try {
            ROOT = Files.createTempDirectory("orderhub-retained-tls");
            SERVER_MATERIAL = SyntheticTlsMaterial.generate(
                    Files.createDirectory(ROOT.resolve("server")),
                    "OrderHub Synthetic Retained Test CA",
                    SERVICE_IDENTITY,
                    SERVICE_IDENTITY + ".cluster.local");
            FOREIGN_MATERIAL = SyntheticTlsMaterial.generate(
                    Files.createDirectory(ROOT.resolve("foreign")),
                    "Unrelated Synthetic CA",
                    SERVICE_IDENTITY);
        } catch (Exception exception) {
            throw new ExceptionInInitializerError(exception);
        }
    }

    @LocalServerPort
    private int port;

    @MockitoBean
    private ResolveExternalIdentityUseCase externalIdentities;

    @MockitoBean
    private IsTenantMembershipOperationallyActiveUseCase memberships;

    @DynamicPropertySource
    static void tlsMaterial(DynamicPropertyRegistry registry) {
        registry.add("spring.ssl.bundle.pem.orderhub-server.keystore.certificate",
                () -> SERVER_MATERIAL.serverCertificate().toUri().toString());
        registry.add("spring.ssl.bundle.pem.orderhub-server.keystore.private-key",
                () -> SERVER_MATERIAL.serverPrivateKey().toUri().toString());
    }

    @AfterAll
    static void deleteMaterial() {
        try {
            FileSystemUtils.deleteRecursively(ROOT);
        } catch (IOException exception) {
            throw new UncheckedIOException(exception);
        }
    }

    /**
     * Why: D027 requires BFF -> OrderHub over HTTPS with normal validation.
     * Covers: externally supplied PEM, real Tomcat TLS, PKIX + SAN verification
     * for the canonical Service identity and its cluster-domain alternate.
     * Prevents: a retained listener that is not actually serving TLS.
     */
    @Test
    void servesHealthOverHttpsToClientTrustingCaForServiceIdentity() throws Exception {
        for (var identity : new String[] {SERVICE_IDENTITY, SERVICE_IDENTITY + ".cluster.local"}) {
            for (var path : new String[] {"/livez", "/readyz"}) {
                var response = SyntheticTlsMaterial.httpsGet(
                        SERVER_MATERIAL.caCertificate(), identity, port, path);

                assertThat(response).startsWith("HTTP/1.1 200").contains("\"status\":\"UP\"");
            }
        }
    }

    /**
     * Why: trust must come from the deployment-owned CA only.
     * Covers: a client trusting an unrelated CA.
     * Prevents: acceptance of a server chain the client never trusted.
     */
    @Test
    void rejectsClientTrustingDifferentCa() {
        assertThatThrownBy(() -> SyntheticTlsMaterial.httpsGet(
                FOREIGN_MATERIAL.caCertificate(), SERVICE_IDENTITY, port, "/livez"))
                .isInstanceOf(SSLHandshakeException.class)
                .hasStackTraceContaining("PKIX path");
    }

    /**
     * Why: the SAN binds the certificate to the stable Service identity.
     * Covers: correct CA but a hostname absent from the SAN.
     * Prevents: a certificate for one identity authenticating another.
     */
    @Test
    void rejectsHostnameAbsentFromCertificateSan() {
        assertThatThrownBy(() -> SyntheticTlsMaterial.httpsGet(
                SERVER_MATERIAL.caCertificate(), "orderhub.other-namespace.svc", port, "/livez"))
                .isInstanceOf(SSLHandshakeException.class)
                .hasStackTraceContaining("No subject alternative DNS name matching");
    }

    /**
     * Why: the retained listener must never serve plaintext application traffic.
     * Covers: a plaintext HTTP request to the retained port.
     * Prevents: silent downgrade to http:// on the retained Service.
     */
    @Test
    void refusesPlaintextHttpOnRetainedPort() throws Exception {
        String response;
        try {
            response = SyntheticTlsMaterial.plainHttpGet(port, "/readyz");
        } catch (IOException closed) {
            response = "";
        }

        assertThat(response)
                .doesNotContain("\"status\":\"UP\"")
                .doesNotStartWith("HTTP/1.1 200");
    }

    /**
     * Why: TLS protects transport; JWT remains the authentication authority.
     * Covers: valid token over HTTPS reaching identity resolution, then wrong
     * issuer, wrong audience, garbage and absent bearer all rejected with 401
     * before identity resolution, exactly as on the plaintext boundary.
     * Prevents: transport change altering bearer semantics.
     */
    @Test
    void preservesRealJwtSemanticsOverHttps(CapturedOutput output) throws Exception {
        var userId = UUID.randomUUID();
        var query = new ResolveExternalIdentityQuery(ISSUER, SUBJECT);
        when(externalIdentities.resolve(query))
                .thenReturn(Optional.of(new ResolvedUserIdentity(userId)));

        assertThat(get("/orders", token(ISSUER, AUDIENCE))).startsWith("HTTP/1.1 405");
        verify(externalIdentities).resolve(query);
        verifyNoInteractions(memberships);

        assertThat(get("/orders", token("https://untrusted-issuer.example.test", AUDIENCE)))
                .startsWith("HTTP/1.1 401");
        assertThat(get("/orders", token(ISSUER, "another-api"))).startsWith("HTTP/1.1 401");
        assertThat(get("/orders", "not-a-jwt")).startsWith("HTTP/1.1 401");
        assertThat(SyntheticTlsMaterial.httpsGet(
                SERVER_MATERIAL.caCertificate(), SERVICE_IDENTITY, port, "/orders"))
                .startsWith("HTTP/1.1 401");

        verify(externalIdentities).resolve(query);
        assertThat(output.getAll())
                .doesNotContain(Files.readString(SERVER_MATERIAL.serverPrivateKey())
                        .lines().skip(1).findFirst().orElseThrow());
    }

    /**
     * Why: an unbound external identity must stay unauthenticated over HTTPS.
     * Covers: valid token whose identity has no internal User binding.
     * Prevents: TLS peer identity substituting for internal identity binding.
     */
    @Test
    void rejectsUnboundIdentityOverHttps() throws Exception {
        when(externalIdentities.resolve(new ResolveExternalIdentityQuery(ISSUER, SUBJECT)))
                .thenReturn(Optional.empty());

        assertThat(get("/orders", token(ISSUER, AUDIENCE))).startsWith("HTTP/1.1 401");
        verifyNoInteractions(memberships);
    }

    private String get(String path, String bearer) throws Exception {
        return SyntheticTlsMaterial.httpsGet(
                SERVER_MATERIAL.caCertificate(), SERVICE_IDENTITY, port, path,
                "Authorization: Bearer " + bearer);
    }

    private static String token(String issuer, String audience) throws JOSEException {
        var now = Instant.now();
        return RealJwtTestSupport.signedToken(
                SIGNING_KEY, issuer, SUBJECT, audience,
                now.plusSeconds(300), now.minusSeconds(30));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RealJwtConfiguration {

        /**
         * Supplies a genuine Nimbus decoder over the synthetic key with the
         * production validation policy.
         *
         * @return real decoder
         * @throws JOSEException when the public key cannot be materialized
         */
        @Bean
        @Primary
        JwtDecoder realJwtDecoder() throws JOSEException {
            return RealJwtTestSupport.decoder(SIGNING_KEY, ISSUER, AUDIENCE);
        }
    }
}

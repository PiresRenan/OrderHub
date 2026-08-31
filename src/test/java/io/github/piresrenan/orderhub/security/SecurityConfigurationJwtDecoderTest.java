package io.github.piresrenan.orderhub.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import io.github.piresrenan.orderhub.users.application.port.in.FindTenantMembershipUseCase;
import io.github.piresrenan.orderhub.users.application.port.in.ResolveExternalIdentityUseCase;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;

class SecurityConfigurationJwtDecoderTest {

    private static final String ISSUER =
            "https://issuer.example.test";

    private static final String OTHER_ISSUER =
            "https://other-issuer.example.test";

    private static final String AUDIENCE =
            "orderhub-api";

    private static final String OTHER_AUDIENCE =
            "other-api";

    private static final String SUBJECT =
            "synthetic-subject-001";

    private static final String KEY_ID =
            "trusted-test-key";

    private KeyPair trustedKeyPair;
    private KeyPair untrustedKeyPair;
    private HttpServer jwkServer;
    private AtomicInteger jwkRequests;
    private String jwkSetUri;

    @BeforeEach
    void startSyntheticJwkEndpoint() throws Exception {
        // Why: decoder composition must exercise a real asymmetric JWK boundary
        // without depending on a live external identity provider.
        // Covers: local synthetic JWK publication and independent trusted and
        // untrusted signing keys.
        // Prevents: mocked decoder wiring hiding signature or network behavior.

        trustedKeyPair =
                generateRsaKeyPair();

        untrustedKeyPair =
                generateRsaKeyPair();

        jwkRequests =
                new AtomicInteger();

        var trustedJwk =
                new RSAKey.Builder(
                        (RSAPublicKey) trustedKeyPair.getPublic())
                        .keyID(
                                KEY_ID)
                        .algorithm(
                                JWSAlgorithm.RS256)
                        .build();

        var jwkSetBody =
                new JWKSet(
                        trustedJwk)
                        .toString()
                        .getBytes(
                                StandardCharsets.UTF_8);

        jwkServer =
                HttpServer.create(
                        new InetSocketAddress(
                                "127.0.0.1",
                                0),
                        0);

        jwkServer.createContext(
                "/jwks",
                exchange -> {
                    jwkRequests.incrementAndGet();

                    exchange
                            .getResponseHeaders()
                            .add(
                                    "Content-Type",
                                    "application/json");

                    exchange.sendResponseHeaders(
                            200,
                            jwkSetBody.length);

                    try (var body =
                            exchange.getResponseBody()) {

                        body.write(
                                jwkSetBody);
                    }
                });

        jwkServer.start();

        jwkSetUri =
                "http://127.0.0.1:"
                        + jwkServer
                                .getAddress()
                                .getPort()
                        + "/jwks";
    }

    @AfterEach
    void stopSyntheticJwkEndpoint() {
        if (jwkServer != null) {
            jwkServer.stop(
                    0);
        }
    }

    @Test
    void createsDecoderWithoutContactingJwkEndpointDuringContextStartup() {
        // Why: OrderHub startup must not depend on external identity-provider
        // availability when the JWK Set location is already configured.
        // Covers: lazy JWK retrieval and production JwtDecoder registration.
        // Prevents: deployments becoming unavailable solely because the identity
        // provider cannot be contacted during application startup.

        contextRunner()
                .run(context -> {
                    assertThat(context)
                            .hasNotFailed()
                            .hasSingleBean(
                                    JwtDecoder.class);

                    assertThat(
                            context.getBean(
                                    JwtDecoder.class))
                            .isInstanceOf(
                                    NimbusJwtDecoder.class);

                    assertThat(jwkRequests)
                            .hasValue(0);
                });
    }

    @Test
    void composesRealSignatureIssuerAndAudienceValidation() {
        // Why: a JwtDecoder bean is useful only if it enforces both cryptographic
        // trust and the complete OrderHub claim-validation policy.
        // Covers: configured JWK Set retrieval, RSA signature verification,
        // issuer validation and audience validation.
        // Prevents: wiring a decoder that accepts a trusted signature for the
        // wrong issuer, wrong resource or an untrusted signing key.

        contextRunner()
                .run(context -> {
                    assertThat(context)
                            .hasNotFailed()
                            .hasSingleBean(
                                    JwtDecoder.class);

                    var decoder =
                            context.getBean(
                                    JwtDecoder.class);

                    var accepted =
                            decoder.decode(
                                    signedToken(
                                            trustedKeyPair,
                                            ISSUER,
                                            AUDIENCE));

                    assertThat(accepted.getIssuer())
                            .hasToString(
                                    ISSUER);

                    assertThat(accepted.getAudience())
                            .contains(
                                    AUDIENCE);

                    assertThat(jwkRequests.get())
                            .isGreaterThanOrEqualTo(1);

                    assertThatThrownBy(() ->
                            decoder.decode(
                                    signedToken(
                                            untrustedKeyPair,
                                            ISSUER,
                                            AUDIENCE)))
                            .isInstanceOf(
                                    JwtException.class);

                    assertThatThrownBy(() ->
                            decoder.decode(
                                    signedToken(
                                            trustedKeyPair,
                                            ISSUER,
                                            OTHER_AUDIENCE)))
                            .isInstanceOf(
                                    JwtException.class);

                    assertThatThrownBy(() ->
                            decoder.decode(
                                    signedToken(
                                            trustedKeyPair,
                                            OTHER_ISSUER,
                                            AUDIENCE)))
                            .isInstanceOf(
                                    JwtException.class);
                });
    }

    /**
     * Creates a production-style Spring context using only explicit external JWT
     * trust configuration.
     *
     * @return isolated Security configuration context
     */
    private ApplicationContextRunner contextRunner() {
        return new ApplicationContextRunner()
                .withUserConfiguration(
                        SecurityConfiguration.class)
                .withBean(
                        ResolveExternalIdentityUseCase.class,
                        () -> query -> {
                            throw new AssertionError(
                                    "JWT decoder composition must not resolve external identity");
                        })
                .withBean(
                        FindTenantMembershipUseCase.class,
                        () -> query -> {
                            throw new AssertionError(
                                    "JWT decoder composition must not resolve tenant membership");
                        })
                .withPropertyValues(
                        "orderhub.security.jwt.issuer="
                                + ISSUER,
                        "orderhub.security.jwt.audience="
                                + AUDIENCE,
                        "orderhub.security.jwt.jwk-set-uri="
                                + jwkSetUri);
    }

    /**
     * Generates independent synthetic RSA key material for cryptographic tests.
     *
     * @return generated RSA key pair
     * @throws Exception when the JVM cannot generate RSA test material
     */
    private KeyPair generateRsaKeyPair() throws Exception {
        var generator =
                KeyPairGenerator.getInstance(
                        "RSA");

        generator.initialize(
                2048);

        return generator.generateKeyPair();
    }

    /**
     * Signs one synthetic access token using the supplied identity and resource
     * claims.
     *
     * @param keyPair signing key material
     * @param issuer exact issuer claim
     * @param audience resource audience claim
     * @return compact serialized signed JWT
     */
    private String signedToken(
            KeyPair keyPair,
            String issuer,
            String audience) {

        try {
            var now =
                    Instant.now();

            var claims =
                    new JWTClaimsSet.Builder()
                            .issuer(
                                    issuer)
                            .subject(
                                    SUBJECT)
                            .audience(
                                    audience)
                            .issueTime(
                                    Date.from(
                                            now.minusSeconds(
                                                    30)))
                            .notBeforeTime(
                                    Date.from(
                                            now.minusSeconds(
                                                    30)))
                            .expirationTime(
                                    Date.from(
                                            now.plusSeconds(
                                                    300)))
                            .build();

            var jwt =
                    new SignedJWT(
                            new JWSHeader.Builder(
                                    JWSAlgorithm.RS256)
                                    .keyID(
                                            KEY_ID)
                                    .build(),
                            claims);

            jwt.sign(
                    new RSASSASigner(
                            (RSAPrivateKey) keyPair.getPrivate()));

            return jwt.serialize();
        } catch (Exception exception) {
            throw new IllegalStateException(
                    "Could not create synthetic JWT",
                    exception);
        }
    }
}

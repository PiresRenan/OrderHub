package io.github.piresrenan.orderhub.security.adapter.in.authentication.jwt;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

class JwtValidationPolicyTest {

    private static final String EXPECTED_ISSUER =
            "https://issuer.example.test";

    private static final String EXPECTED_AUDIENCE =
            "orderhub-api";

    private static final String SUBJECT =
            "synthetic-subject-001";

    private static KeyPair trustedKeyPair;
    private static KeyPair untrustedKeyPair;

    @BeforeAll
    static void generateSyntheticKeys() throws Exception {
        // Why: signature verification must exercise real asymmetric
        // cryptography without relying on any live identity provider.
        // Covers: independent trusted and untrusted RSA key material.
        // Prevents: mocked cryptography giving false confidence in JWT
        // signature validation.

        trustedKeyPair = generateRsaKeyPair();
        untrustedKeyPair = generateRsaKeyPair();
    }

    @Test
    void acceptsProperlySignedTokenWithRequiredClaims() throws Exception {
        // Why: a valid access token must survive the complete cryptographic and
        // claim-validation boundary.
        // Covers: RS256 signature, issuer, audience, expiration and not-before
        // validation using synthetic real cryptography.
        // Prevents: an over-restrictive policy rejecting legitimate access
        // tokens.

        var decoder = decoderFor(
                (RSAPublicKey) trustedKeyPair.getPublic());

        var now = Instant.now();

        var token = signedToken(
                trustedKeyPair,
                EXPECTED_ISSUER,
                EXPECTED_AUDIENCE,
                SUBJECT,
                now.minusSeconds(30),
                now.plusSeconds(300));

        var jwt = decoder.decode(token);

        assertThat(jwt.getIssuer().toString())
                .isEqualTo(EXPECTED_ISSUER);

        assertThat(jwt.getAudience())
                .contains(EXPECTED_AUDIENCE);

        assertThat(jwt.getSubject())
                .isEqualTo(SUBJECT);
    }

    @Test
    void rejectsTokenSignedByUntrustedKey() throws Exception {
        // Why: claims are irrelevant if the token was not signed by trusted key
        // material.
        // Covers: actual RS256 signature verification.
        // Prevents: forged tokens with otherwise valid-looking claims being
        // accepted.

        var decoder = decoderFor(
                (RSAPublicKey) trustedKeyPair.getPublic());

        var now = Instant.now();

        var forgedToken = signedToken(
                untrustedKeyPair,
                EXPECTED_ISSUER,
                EXPECTED_AUDIENCE,
                SUBJECT,
                now.minusSeconds(30),
                now.plusSeconds(300));

        assertThatThrownBy(() ->
                decoder.decode(forgedToken))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsUnexpectedIssuer() throws Exception {
        // Why: a cryptographically valid token issued by another authority must
        // not authenticate against this OrderHub resource server.
        // Covers: exact issuer validation.
        // Prevents: cross-issuer token acceptance.

        var decoder = decoderFor(
                (RSAPublicKey) trustedKeyPair.getPublic());

        var now = Instant.now();

        var token = signedToken(
                trustedKeyPair,
                "https://different-issuer.example.test",
                EXPECTED_AUDIENCE,
                SUBJECT,
                now.minusSeconds(30),
                now.plusSeconds(300));

        assertThatThrownBy(() ->
                decoder.decode(token))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsUnexpectedAudience() throws Exception {
        // Why: a token may be valid for its issuer but intended for another
        // resource server.
        // Covers: required OrderHub audience validation.
        // Prevents: valid tokens issued for unrelated APIs being replayed
        // against OrderHub.

        var decoder = decoderFor(
                (RSAPublicKey) trustedKeyPair.getPublic());

        var now = Instant.now();

        var token = signedToken(
                trustedKeyPair,
                EXPECTED_ISSUER,
                "another-api",
                SUBJECT,
                now.minusSeconds(30),
                now.plusSeconds(300));

        assertThatThrownBy(() ->
                decoder.decode(token))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsExpiredToken() throws Exception {
        // Why: possession of a formerly valid credential must not authorize
        // requests indefinitely.
        // Covers: expiration validation beyond Spring Security's normal clock
        // skew allowance.
        // Prevents: replay of expired bearer credentials.

        var decoder = decoderFor(
                (RSAPublicKey) trustedKeyPair.getPublic());

        var now = Instant.now();

        var token = signedToken(
                trustedKeyPair,
                EXPECTED_ISSUER,
                EXPECTED_AUDIENCE,
                SUBJECT,
                now.minusSeconds(600),
                now.minusSeconds(300));

        assertThatThrownBy(() ->
                decoder.decode(token))
                .isInstanceOf(JwtException.class);
    }

    @Test
    void rejectsTokenBeforeNotBeforeInstant() throws Exception {
        // Why: a correctly signed credential must not be usable before the
        // authority-defined validity window begins.
        // Covers: nbf validation beyond normal clock skew allowance.
        // Prevents: premature use of future-dated credentials.

        var decoder = decoderFor(
                (RSAPublicKey) trustedKeyPair.getPublic());

        var now = Instant.now();

        var token = signedToken(
                trustedKeyPair,
                EXPECTED_ISSUER,
                EXPECTED_AUDIENCE,
                SUBJECT,
                now.plusSeconds(300),
                now.plusSeconds(600));

        assertThatThrownBy(() ->
                decoder.decode(token))
                .isInstanceOf(JwtException.class);
    }

    /**
     * Creates the JWT decoder used by the production validation policy test.
     *
     * @param publicKey trusted RSA verification key
     * @return decoder configured with OrderHub JWT validation policy
     */
    private NimbusJwtDecoder decoderFor(
            RSAPublicKey publicKey) {

        var decoder = NimbusJwtDecoder
                .withPublicKey(publicKey)
                .signatureAlgorithm(SignatureAlgorithm.RS256)
                .build();

        decoder.setJwtValidator(
                new JwtValidationPolicy(
                        EXPECTED_ISSUER,
                        EXPECTED_AUDIENCE));

        return decoder;
    }

    /**
     * Generates independent synthetic RSA key material for cryptographic tests.
     *
     * @return generated RSA key pair
     * @throws Exception when the JVM cannot provide the required RSA primitive
     */
    private static KeyPair generateRsaKeyPair()
            throws Exception {

        var generator = KeyPairGenerator.getInstance("RSA");

        generator.initialize(2048);

        return generator.generateKeyPair();
    }

    /**
     * Produces a real compact RS256 JWT using synthetic test-only key material.
     *
     * @param keyPair signing key pair
     * @param issuer issuer claim
     * @param audience audience claim
     * @param subject subject claim
     * @param notBefore earliest accepted instant
     * @param expiresAt expiration instant
     * @return compact signed JWT
     * @throws Exception when signing cannot be completed
     */
    private String signedToken(
            KeyPair keyPair,
            String issuer,
            String audience,
            String subject,
            Instant notBefore,
            Instant expiresAt)
            throws Exception {

        var claims = new JWTClaimsSet.Builder()
                .issuer(issuer)
                .subject(subject)
                .audience(audience)
                .issueTime(
                        Date.from(
                                Instant.now()))
                .notBeforeTime(
                        Date.from(
                                notBefore))
                .expirationTime(
                        Date.from(
                                expiresAt))
                .build();

        var jwt = new SignedJWT(
                new JWSHeader.Builder(
                        JWSAlgorithm.RS256)
                        .keyID("synthetic-test-key")
                        .build(),
                claims);

        jwt.sign(
                new RSASSASigner(
                        (RSAPrivateKey) keyPair.getPrivate()));

        return jwt.serialize();
    }
}

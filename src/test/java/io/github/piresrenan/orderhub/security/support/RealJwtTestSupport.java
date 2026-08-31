package io.github.piresrenan.orderhub.security.support;

import java.time.Instant;
import java.util.Date;

import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;

import io.github.piresrenan.orderhub.security.adapter.in.authentication.jwt.JwtValidationPolicy;

/**
 * Provides synthetic cryptographic infrastructure for real Resource Server
 * tests.
 *
 * <p>This support remains strictly test-only. It generates ephemeral RSA keys,
 * signs compact JWTs and builds a genuine Nimbus decoder while retaining the
 * production JWT validation policy.
 */
public final class RealJwtTestSupport {

    private RealJwtTestSupport() {
    }

    /**
     * Generates an ephemeral RSA key for one test JVM.
     *
     * @param keyId synthetic key identifier
     * @return generated RSA key pair
     */
    public static RSAKey generateRsaKey(
            String keyId) {

        try {
            return new RSAKeyGenerator(
                    2048)
                    .keyID(
                            keyId)
                    .generate();
        } catch (JOSEException exception) {
            throw new ExceptionInInitializerError(
                    exception);
        }
    }

    /**
     * Creates and signs one compact RS256 JWT using synthetic test material.
     *
     * @param signingKey ephemeral signing key
     * @param issuer exact issuer claim
     * @param subject exact subject claim
     * @param audience expected resource audience
     * @param expiresAt expiration instant
     * @param notBefore not-before instant
     * @return serialized signed JWT
     * @throws JOSEException when signing fails
     */
    public static String signedToken(
            RSAKey signingKey,
            String issuer,
            String subject,
            String audience,
            Instant expiresAt,
            Instant notBefore)
            throws JOSEException {

        var claims =
                new JWTClaimsSet.Builder()
                        .issuer(
                                issuer)
                        .subject(
                                subject)
                        .audience(
                                audience)
                        .issueTime(
                                Date.from(
                                        Instant.now()
                                                .minusSeconds(
                                                        30)))
                        .notBeforeTime(
                                Date.from(
                                        notBefore))
                        .expirationTime(
                                Date.from(
                                        expiresAt))
                        .build();

        var signedJwt =
                new SignedJWT(
                        new JWSHeader.Builder(
                                JWSAlgorithm.RS256)
                                .type(
                                        JOSEObjectType.JWT)
                                .keyID(
                                        signingKey.getKeyID())
                                .build(),
                        claims);

        signedJwt.sign(
                new RSASSASigner(
                        signingKey));

        return signedJwt.serialize();
    }

    /**
     * Creates a genuine Nimbus decoder backed by the supplied synthetic public
     * key and the production validation policy.
     *
     * @param signingKey key whose public component is trusted
     * @param issuer expected issuer
     * @param audience expected audience
     * @return real JWT decoder
     * @throws JOSEException when the RSA public key cannot be materialized
     */
    public static JwtDecoder decoder(
            RSAKey signingKey,
            String issuer,
            String audience)
            throws JOSEException {

        var decoder =
                NimbusJwtDecoder
                        .withPublicKey(
                                signingKey
                                        .toRSAPublicKey())
                        .signatureAlgorithm(
                                SignatureAlgorithm.RS256)
                        .build();

        decoder.setJwtValidator(
                new JwtValidationPolicy(
                        issuer,
                        audience));

        return decoder;
    }
}

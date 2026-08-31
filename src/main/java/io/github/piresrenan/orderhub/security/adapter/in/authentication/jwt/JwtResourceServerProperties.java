package io.github.piresrenan.orderhub.security.adapter.in.authentication.jwt;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Carries the externalized trust configuration required by the OrderHub JWT
 * Resource Server.
 *
 * <p>No development issuer, audience, signing key or JWK endpoint is supplied
 * by this contract. All values must be provided explicitly by the deployment
 * environment.
 *
 * @param issuer exact trusted JWT issuer
 * @param audience exact audience required for OrderHub access tokens
 * @param jwkSetUri trusted JWK Set endpoint used for signature verification
 */
@ConfigurationProperties(prefix = "orderhub.security.jwt")
public record JwtResourceServerProperties(
        String issuer,
        String audience,
        String jwkSetUri) {

    /**
     * Ensures that the complete production JWT trust boundary is configured.
     *
     * <p>Non-blank configured values are retained exactly. This validation does
     * not normalize, trim, lowercase or otherwise rewrite security identifiers.
     *
     * @throws IllegalArgumentException when issuer, audience or JWK Set location
     *                                  is missing or blank
     */
    public JwtResourceServerProperties {
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalArgumentException(
                    "JWT issuer is required");
        }

        if (audience == null || audience.isBlank()) {
            throw new IllegalArgumentException(
                    "JWT audience is required");
        }

        if (jwkSetUri == null || jwkSetUri.isBlank()) {
            throw new IllegalArgumentException(
                    "JWT JWK Set URI is required");
        }
    }
}

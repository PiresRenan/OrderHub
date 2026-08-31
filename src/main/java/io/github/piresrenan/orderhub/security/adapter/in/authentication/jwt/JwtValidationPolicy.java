package io.github.piresrenan.orderhub.security.adapter.in.authentication.jwt;

import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtAudienceValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;

/**
 * Defines the validation policy applied to externally issued JWT access tokens.
 *
 * <p>The policy delegates cryptographic signature verification to the configured
 * {@code JwtDecoder}. It complements that boundary with Spring Security's
 * standard temporal validation, exact issuer validation and the audience
 * required by OrderHub.
 *
 * <p>No JWT claim extracted here becomes trusted Tenant authority. Establishing
 * an internal User and trusted Tenant context remains a separate application
 * boundary.
 */
public final class JwtValidationPolicy
        implements OAuth2TokenValidator<Jwt> {

    private final OAuth2TokenValidator<Jwt> delegate;

    /**
     * Creates the validation policy required by the OrderHub resource server.
     *
     * @param expectedIssuer exact issuer that authenticated tokens must declare
     * @param expectedAudience audience that authenticated tokens must contain
     */
    public JwtValidationPolicy(
            String expectedIssuer,
            String expectedAudience) {

        this.delegate =
                new DelegatingOAuth2TokenValidator<>(
                        JwtValidators.createDefaultWithIssuer(
                                expectedIssuer),
                        new JwtAudienceValidator(
                                expectedAudience));
    }

    /**
     * Applies the complete claim-validation policy to a decoded JWT.
     *
     * @param token JWT whose temporal, issuer and audience claims are validated
     * @return aggregated Spring Security validation result
     */
    @Override
    public OAuth2TokenValidatorResult validate(
            Jwt token) {

        return delegate.validate(token);
    }
}
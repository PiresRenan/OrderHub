package io.github.piresrenan.orderhub.security.adapter.in.authentication.jwt;

import org.springframework.security.oauth2.core.DelegatingOAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.core.OAuth2TokenValidatorResult;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwtAudienceValidator;
import org.springframework.security.oauth2.jwt.JwtValidators;
import org.springframework.security.oauth2.jwt.JwtTimestampValidator;
import org.springframework.security.oauth2.jwt.JwtIssuerValidator;
import org.springframework.security.oauth2.jwt.JwtClaimValidator;

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
        this(expectedIssuer, expectedAudience, JwtTokenProfile.GENERIC);
    }

    /** Preserves explicit profile construction; Cognito still requires a client allowlist. */
    public JwtValidationPolicy(String expectedIssuer, String expectedAudience, JwtTokenProfile profile) {
        this(expectedIssuer, expectedAudience, profile, java.util.List.of());
    }

    /** Requires bounded expiry for every provider, plus access purpose and an admitted App Client for explicit Cognito trust. */
    public JwtValidationPolicy(String expectedIssuer, String expectedAudience, JwtTokenProfile profile,
            java.util.List<String> allowedClientIds) {
        java.util.Objects.requireNonNull(profile, "JWT token profile is required").validateAudience(expectedAudience);
        var clients = java.util.Set.copyOf(profile.validateAllowedClientIds(allowedClientIds));
        var timestamps = new JwtTimestampValidator();
        timestamps.setAllowEmptyExpiryClaim(false);
        var validators = new java.util.ArrayList<OAuth2TokenValidator<Jwt>>();
        validators.add(JwtValidators.createDefaultWithValidators(timestamps, new JwtIssuerValidator(expectedIssuer)));
        validators.add(new JwtAudienceValidator(expectedAudience));
        if (profile == JwtTokenProfile.COGNITO) {
            validators.add(new JwtClaimValidator<Object>("token_use", value -> "access".equals(value)));
            validators.add(new JwtClaimValidator<Object>("client_id",
                    value -> value instanceof String client && clients.contains(client)));
        }
        this.delegate = new DelegatingOAuth2TokenValidator<>(validators);
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

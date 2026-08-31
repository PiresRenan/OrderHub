package io.github.piresrenan.orderhub.security.adapter.in.authentication.jwt;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.authentication.BadCredentialsException;
import org.springframework.security.oauth2.jwt.Jwt;

import io.github.piresrenan.orderhub.security.adapter.in.authentication.AuthenticatedUserAuthenticationToken;
import io.github.piresrenan.orderhub.security.application.port.in.ResolveAuthenticatedUserQuery;
import io.github.piresrenan.orderhub.security.application.port.in.ResolveAuthenticatedUserUseCase;

/**
 * Adapts a validated JWT into OrderHub's internal authenticated User identity.
 *
 * <p>JWT signature, temporal, issuer and audience validation occur before this
 * converter. This adapter uses the validated issuer and subject only as input to
 * the application identity-resolution boundary and does not propagate JWT state
 * into the resulting authentication.
 */
public final class AuthenticatedUserJwtAuthenticationConverter
        implements Converter<Jwt, AbstractAuthenticationToken> {

    private static final String AUTHENTICATION_FAILURE_MESSAGE =
            "Bearer authentication failed";

    private final ResolveAuthenticatedUserUseCase authenticatedUsers;

    /**
     * Creates the JWT-to-internal-principal adapter.
     *
     * @param authenticatedUsers application boundary that resolves external
     *                           identity into an internal User
     * @throws IllegalArgumentException when identity resolution is unavailable
     */
    public AuthenticatedUserJwtAuthenticationConverter(
            ResolveAuthenticatedUserUseCase authenticatedUsers) {

        if (authenticatedUsers == null) {
            throw new IllegalArgumentException(
                    "Authenticated user resolver is required");
        }

        this.authenticatedUsers = authenticatedUsers;
    }

    /**
     * Resolves the JWT external identity and returns authentication containing
     * only the internal OrderHub principal.
     *
     * @param jwt previously validated JWT
     * @return authenticated internal User token
     * @throws BadCredentialsException when the JWT cannot be mapped to a valid
     *                                 internal User identity
     */
    @Override
    public AbstractAuthenticationToken convert(
            Jwt jwt) {

        if (jwt == null) {
            throw authenticationFailure();
        }

        var issuer =
                jwt.getClaimAsString(
                        "iss");

        var subject =
                jwt.getSubject();

        if (issuer == null
                || issuer.isBlank()
                || subject == null
                || subject.isBlank()) {
            throw authenticationFailure();
        }

        var principal =
                authenticatedUsers
                        .resolve(
                                new ResolveAuthenticatedUserQuery(
                                        issuer,
                                        subject))
                        .orElseThrow(
                                AuthenticatedUserJwtAuthenticationConverter::
                                        authenticationFailure);

        return new AuthenticatedUserAuthenticationToken(
                principal);
    }

    /**
     * Creates the stable non-enumerating authentication failure exposed to the
     * Spring Security authentication pipeline.
     *
     * @return generic authentication failure without identity details
     */
    private static BadCredentialsException authenticationFailure() {
        return new BadCredentialsException(
                AUTHENTICATION_FAILURE_MESSAGE);
    }
}

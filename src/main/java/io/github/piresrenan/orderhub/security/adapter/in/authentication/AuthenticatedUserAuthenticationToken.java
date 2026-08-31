package io.github.piresrenan.orderhub.security.adapter.in.authentication;

import java.util.List;

import org.springframework.security.authentication.AbstractAuthenticationToken;

import io.github.piresrenan.orderhub.security.application.model.AuthenticatedUserPrincipal;

/**
 * Represents a successfully authenticated OrderHub User inside Spring Security.
 *
 * <p>The authentication contains only the internal User principal required by
 * downstream security adapters. Bearer credentials, JWT claims and external
 * identity-provider identifiers are deliberately discarded.
 */
public final class AuthenticatedUserAuthenticationToken
        extends AbstractAuthenticationToken {

    private final AuthenticatedUserPrincipal principal;

    /**
     * Creates an immutable authenticated token for an already resolved internal
     * User.
     *
     * @param principal authenticated internal User principal
     * @throws IllegalArgumentException when the principal is missing
     */
    public AuthenticatedUserAuthenticationToken(
            AuthenticatedUserPrincipal principal) {

        super(List.of());

        if (principal == null) {
            throw new IllegalArgumentException(
                    "Authenticated user principal is required");
        }

        this.principal = principal;

        super.setAuthenticated(true);
    }

    /**
     * Returns the authentication-neutral internal User principal.
     *
     * @return authenticated internal User
     */
    @Override
    public AuthenticatedUserPrincipal getPrincipal() {
        return principal;
    }

    /**
     * Returns no bearer credentials because validated access-token material is
     * not retained after authentication.
     *
     * @return always {@code null}
     */
    @Override
    public Object getCredentials() {
        return null;
    }

    /**
     * Returns a stable non-identifying framework principal name.
     *
     * @return constant authenticated principal name
     */
    @Override
    public String getName() {
        return "authenticated-user";
    }

    /**
     * Prevents callers from promoting an arbitrary token instance into trusted
     * authenticated state after construction.
     *
     * @param authenticated requested authentication state
     * @throws IllegalArgumentException when attempting to set authenticated state
     */
    @Override
    public void setAuthenticated(
            boolean authenticated) {

        if (authenticated) {
            throw new IllegalArgumentException(
                    "Authenticated state cannot be promoted externally");
        }

        super.setAuthenticated(false);
    }
}

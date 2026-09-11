package io.github.piresrenan.orderhub.security.adapter.in.authentication;

import java.util.List;
import java.util.Objects;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import io.github.piresrenan.orderhub.security.application.model.VerifiedExternalIdentity;

/** Discards bearer credentials and exposes zero granted authorities after normal JWT verification. */
public final class VerifiedExternalIdentityAuthenticationToken extends AbstractAuthenticationToken {
    private final VerifiedExternalIdentity principal;
    /** Requires the supplied owner contracts; construction performs no lifecycle mutation or independent commit. */
    public VerifiedExternalIdentityAuthenticationToken(VerifiedExternalIdentity principal) {
        super(List.of()); this.principal = Objects.requireNonNull(principal); super.setAuthenticated(true);
    }
    /** Exposes only the verified external identity fact, not an internal business principal. */
    /** Returns no bearer material after verification, preventing credential retention in the security context. */
    /** Uses a constant framework name so logging need not reveal a provider subject. */
    /** Allows trust to be revoked but prevents callers from promoting token authentication state. */
    @Override public VerifiedExternalIdentity getPrincipal() { return principal; }
    @Override public Object getCredentials() { return null; }
    @Override public String getName() { return "verified-external-identity"; }
    @Override public void setAuthenticated(boolean authenticated) {
        if (authenticated) { throw new IllegalArgumentException("Authenticated state cannot be promoted externally"); }
        super.setAuthenticated(false);
    }
}

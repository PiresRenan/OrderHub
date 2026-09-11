package io.github.piresrenan.orderhub.users.application.port.out;

/** Security supplies the configured issuer allowlist; Users owns binding lifecycle and never fetches provider metadata. */
@org.springframework.modulith.NamedInterface("identity-provider-trust")
@FunctionalInterface
public interface TrustedExternalIdentityProviders {
    /** Checks only explicitly configured server trust; no token-controlled discovery or network lookup is allowed. */
    boolean isTrusted(String issuer);
}

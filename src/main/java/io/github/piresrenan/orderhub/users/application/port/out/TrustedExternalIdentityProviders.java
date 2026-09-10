package io.github.piresrenan.orderhub.users.application.port.out;

/** Security supplies the configured issuer allowlist; Users owns binding lifecycle and never fetches provider metadata. */
@org.springframework.modulith.NamedInterface("identity-provider-trust")
@FunctionalInterface
public interface TrustedExternalIdentityProviders {
    boolean isTrusted(String issuer);
}

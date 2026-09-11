package io.github.piresrenan.orderhub.security.application.model;

/** Verified authentication fact only: no internal User, Tenant, role or permission. */
public record VerifiedExternalIdentity(String issuer, String subject) {
    /** Rejects missing verified identity facts while preserving their exact case and whitespace. */
    public VerifiedExternalIdentity {
        if (issuer == null || issuer.isBlank() || subject == null || subject.isBlank()) {
            throw new IllegalArgumentException("Verified external identity is required");
        }
    }
    /** Redacts credential or provider identity data from incidental textual logging. */
    @Override public String toString() { return "VerifiedExternalIdentity[redacted]"; }
}

package io.github.piresrenan.orderhub.security.application.port.in;

/**
 * Carries the exact external identity that must be resolved to an authenticated
 * internal OrderHub User.
 *
 * <p>
 * Issuer and subject are provider-owned identity values and are therefore
 * preserved exactly. This contract performs no trimming, lowercasing or
 * provider-specific normalization.
 * </p>
 *
 * @param issuer exact external identity issuer
 * @param subject exact external identity subject
 */
public record ResolveAuthenticatedUserQuery(
        String issuer,
        String subject) {

    /**
     * Validates the minimum external identity required for internal User
     * resolution.
     *
     * @throws IllegalArgumentException when issuer or subject is absent or blank
     */
    public ResolveAuthenticatedUserQuery {
        if (issuer == null || issuer.isBlank()) {
            throw new IllegalArgumentException(
                    "External identity issuer is required");
        }

        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException(
                    "External identity subject is required");
        }
    }
}

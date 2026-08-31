package io.github.piresrenan.orderhub.users.application.port.in;

/**
 * Carries the complete provider identity required to resolve an internal
 * OrderHub User.
 *
 * <p>
 * Validation rejects structurally unusable values but preserves valid issuer
 * and subject values exactly.
 * </p>
 *
 * @param issuer  exact external identity issuer
 * @param subject exact external identity subject
 */
public record ResolveExternalIdentityQuery(
        String issuer,
        String subject) {

    /**
     * Validates structural completeness before the query can cross the
     * application output boundary.
     *
     * @throws IllegalArgumentException when issuer or subject is null or blank
     */
    public ResolveExternalIdentityQuery {
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

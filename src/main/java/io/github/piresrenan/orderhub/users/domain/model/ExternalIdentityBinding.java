package io.github.piresrenan.orderhub.users.domain.model;

import java.util.UUID;

/**
 * Represents the durable association between an externally authenticated
 * identity and OrderHub's stable internal User identity.
 *
 * <p>
 * External provider identity is modeled independently from the User aggregate
 * so the internal User remains authentication-provider neutral.
 * </p>
 *
 * <p>
 * Issuer and subject values are preserved exactly after validation. This model
 * deliberately performs no trimming, case conversion or provider-specific
 * normalization because those transformations could change identity
 * semantics.
 * </p>
 */
public final class ExternalIdentityBinding {

    private final String issuer;
    private final String subject;
    private final UUID userId;

    /**
     * Builds a binding from values that have already satisfied the domain
     * invariants.
     *
     * @param issuer  exact external identity issuer
     * @param subject exact external subject within the issuer namespace
     * @param userId  stable internal OrderHub User identifier
     */
    private ExternalIdentityBinding(
            String issuer,
            String subject,
            UUID userId) {

        this.issuer = issuer;
        this.subject = subject;
        this.userId = userId;
    }

    /**
     * Creates a new association between an external provider identity and an
     * internal OrderHub User.
     *
     * <p>
     * Non-blank issuer and subject values are retained exactly as supplied.
     * Validation determines only whether the identity values are structurally
     * usable; it does not normalize them.
     * </p>
     *
     * @param issuer  external identity issuer
     * @param subject external identity subject
     * @param userId  internal User identifier
     * @return valid external identity binding
     * @throws IllegalArgumentException when issuer or subject is null/blank, or
     *                                  when userId is null
     */
    public static ExternalIdentityBinding create(
            String issuer,
            String subject,
            UUID userId) {

        validate(issuer, subject, userId);

        return new ExternalIdentityBinding(
                issuer,
                subject,
                userId);
    }

    /**
     * Reconstructs a persisted external identity binding while reapplying the
     * same invariants used when creating new state.
     *
     * <p>
     * Persisted issuer and subject values are not transformed during
     * reconstruction.
     * </p>
     *
     * @param issuer  persisted external identity issuer
     * @param subject persisted external identity subject
     * @param userId  persisted internal User identifier
     * @return valid reconstructed external identity binding
     * @throws IllegalArgumentException when persisted state violates an
     *                                  invariant
     */
    public static ExternalIdentityBinding rehydrate(
            String issuer,
            String subject,
            UUID userId) {

        validate(issuer, subject, userId);

        return new ExternalIdentityBinding(
                issuer,
                subject,
                userId);
    }

    /**
     * Enforces the minimum structural invariants required for an external
     * identity binding without changing provider identity values.
     *
     * @param issuer  issuer to validate
     * @param subject subject to validate
     * @param userId  internal User identifier to validate
     * @throws IllegalArgumentException when any required identity component is
     *                                  unusable
     */
    private static void validate(
            String issuer,
            String subject,
            UUID userId) {

        if (issuer == null || issuer.isBlank()) {
            throw new IllegalArgumentException(
                    "External identity issuer is required");
        }

        if (subject == null || subject.isBlank()) {
            throw new IllegalArgumentException(
                    "External identity subject is required");
        }

        if (userId == null) {
            throw new IllegalArgumentException(
                    "External identity user id is required");
        }
    }

    /**
     * Returns the exact external issuer identity supplied by the provider.
     *
     * @return external identity issuer
     */
    public String issuer() {
        return issuer;
    }

    /**
     * Returns the exact subject identity inside the issuer namespace.
     *
     * @return external identity subject
     */
    public String subject() {
        return subject;
    }

    /**
     * Returns the internal OrderHub User associated with this external
     * identity.
     *
     * @return internal User identifier
     */
    public UUID userId() {
        return userId;
    }
}

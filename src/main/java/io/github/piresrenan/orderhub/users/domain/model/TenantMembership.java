package io.github.piresrenan.orderhub.users.domain.model;

import java.util.UUID;

/**
 * Represents the identity association between one User and one Tenant.
 *
 * <p>
 * Membership expresses association only. It deliberately contains no roles,
 * permissions or authentication semantics.
 * </p>
 */
public final class TenantMembership {

    private final UUID userId;
    private final UUID tenantId;

    /**
     * Builds a membership from identifiers that have already satisfied its
     * invariants.
     *
     * @param userId internal User identifier
     * @param tenantId associated Tenant identifier
     */
    private TenantMembership(
            UUID userId,
            UUID tenantId) {

        this.userId = userId;
        this.tenantId = tenantId;
    }

    /**
     * Creates a new association between one User and one Tenant.
     *
     * <p>
     * Pair uniqueness is a repository/persistence invariant because one isolated
     * domain object cannot determine whether another equivalent membership
     * already exists.
     * </p>
     *
     * @param userId internal User identifier
     * @param tenantId associated Tenant identifier
     * @return valid TenantMembership
     * @throws IllegalArgumentException when either required identifier is missing
     */
    public static TenantMembership create(
            UUID userId,
            UUID tenantId) {

        validateRequiredIds(
                userId,
                tenantId);

        return new TenantMembership(
                userId,
                tenantId);
    }

    /**
     * Reconstructs an existing membership from persisted identity state.
     *
     * @param userId persisted internal User identifier
     * @param tenantId persisted Tenant identifier
     * @return valid reconstructed TenantMembership
     * @throws IllegalArgumentException when persisted state violates an invariant
     */
    public static TenantMembership rehydrate(
            UUID userId,
            UUID tenantId) {

        validateRequiredIds(
                userId,
                tenantId);

        return new TenantMembership(
                userId,
                tenantId);
    }

    /**
     * Enforces the identifiers required for a membership association to exist.
     *
     * @param userId User identifier to validate
     * @param tenantId Tenant identifier to validate
     * @throws IllegalArgumentException when either identifier is null
     */
    private static void validateRequiredIds(
            UUID userId,
            UUID tenantId) {

        if (userId == null) {
            throw new IllegalArgumentException(
                    "Membership user id is required");
        }

        if (tenantId == null) {
            throw new IllegalArgumentException(
                    "Membership tenant id is required");
        }
    }

    /**
     * Returns the internal User participating in this membership.
     *
     * @return User identifier
     */
    public UUID userId() {
        return userId;
    }

    /**
     * Returns the Tenant participating in this membership.
     *
     * @return Tenant identifier
     */
    public UUID tenantId() {
        return tenantId;
    }
}

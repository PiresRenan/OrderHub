package io.github.piresrenan.orderhub.users.domain.model;

import java.util.UUID;

/**
 * Represents the durable identity association between one User and one Tenant.
 *
 * <p>
 * Membership remains separate from roles, permissions, authentication
 * credentials, Staff persona and Customer persona. OH-019 adds only operational
 * lifecycle state to the existing relationship.
 * </p>
 */
public final class TenantMembership {

    private final UUID userId;
    private final UUID tenantId;
    private final TenantMembershipStatus status;

    /**
     * Builds a membership from identity and lifecycle state that have already
     * satisfied its invariants.
     *
     * @param userId internal User identifier
     * @param tenantId associated Tenant identifier
     * @param status operational lifecycle of the relationship
     */
    private TenantMembership(
            UUID userId,
            UUID tenantId,
            TenantMembershipStatus status) {

        this.userId = userId;
        this.tenantId = tenantId;
        this.status = status;
    }

    /**
     * Creates a new operational membership.
     *
     * <p>
     * Newly established memberships begin ACTIVE. Pair uniqueness remains a
     * repository/PostgreSQL invariant.
     * </p>
     *
     * @param userId internal User identifier
     * @param tenantId associated Tenant identifier
     * @return new ACTIVE membership
     * @throws IllegalArgumentException when either required identifier is missing
     */
    public static TenantMembership create(
            UUID userId,
            UUID tenantId) {

        validateRequiredState(
                userId,
                tenantId,
                TenantMembershipStatus.ACTIVE);

        return new TenantMembership(
                userId,
                tenantId,
                TenantMembershipStatus.ACTIVE);
    }

    /**
     * Strictly reconstructs persisted membership lifecycle state.
     *
     * @param userId persisted internal User identifier
     * @param tenantId persisted Tenant identifier
     * @param status persisted operational lifecycle
     * @return reconstructed membership
     * @throws IllegalArgumentException when persisted state violates an invariant
     */
    public static TenantMembership rehydrate(
            UUID userId,
            UUID tenantId,
            TenantMembershipStatus status) {

        validateRequiredState(
                userId,
                tenantId,
                status);

        return new TenantMembership(
                userId,
                tenantId,
                status);
    }

    /**
     * Enforces the identity and lifecycle state required for a membership
     * association to exist.
     *
     * @param userId User identifier to validate
     * @param tenantId Tenant identifier to validate
     * @param status operational lifecycle to validate
     * @throws IllegalArgumentException when any required value is null
     */
    private static void validateRequiredState(
            UUID userId,
            UUID tenantId,
            TenantMembershipStatus status) {

        if (userId == null) {
            throw new IllegalArgumentException(
                    "Membership user id is required");
        }

        if (tenantId == null) {
            throw new IllegalArgumentException(
                    "Membership tenant id is required");
        }

        if (status == null) {
            throw new IllegalArgumentException(
                    "Membership status is required");
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

    /**
     * Returns the persisted operational lifecycle of this relationship.
     *
     * @return current membership status
     */
    public TenantMembershipStatus status() {
        return status;
    }

    /**
     * Reports whether this relationship may participate in new trusted Tenant
     * context establishment.
     *
     * @return true only for ACTIVE membership state
     */
    public boolean isOperationallyActive() {
        return status == TenantMembershipStatus.ACTIVE;
    }
}

package io.github.piresrenan.orderhub.organizations.domain.model;

import java.util.UUID;

/**
 * Represents the organizational placement of one Tenant under one Organization.
 *
 * <p>
 * Placement expresses association only. It deliberately contains no
 * authorization, membership, role, resource-permission or Tenant lifecycle
 * semantics.
 * </p>
 *
 * <p>
 * Tenant-to-Organization cardinality is a repository/persistence invariant
 * because one isolated domain object cannot determine whether another placement
 * already exists for the same Tenant.
 * </p>
 */
public final class OrganizationTenantPlacement {

    private final UUID organizationId;
    private final UUID tenantId;

    private OrganizationTenantPlacement(
            UUID organizationId,
            UUID tenantId) {

        this.organizationId = organizationId;
        this.tenantId = tenantId;
    }

    /**
     * Creates an association between one Organization and one Tenant.
     *
     * @param organizationId internal Organization identifier
     * @param tenantId internal Tenant identifier
     * @return valid OrganizationTenantPlacement
     * @throws IllegalArgumentException when either required identifier is missing
     */
    public static OrganizationTenantPlacement create(
            UUID organizationId,
            UUID tenantId) {

        validateRequiredIds(
                organizationId,
                tenantId);

        return new OrganizationTenantPlacement(
                organizationId,
                tenantId);
    }

    private static void validateRequiredIds(
            UUID organizationId,
            UUID tenantId) {

        if (organizationId == null) {
            throw new IllegalArgumentException(
                    "Placement organization id is required");
        }

        if (tenantId == null) {
            throw new IllegalArgumentException(
                    "Placement tenant id is required");
        }
    }

    /**
     * Returns the Organization that owns this placement.
     *
     * @return Organization identifier
     */
    public UUID organizationId() {
        return organizationId;
    }

    /**
     * Returns the Tenant participating in this placement.
     *
     * @return Tenant identifier
     */
    public UUID tenantId() {
        return tenantId;
    }
}

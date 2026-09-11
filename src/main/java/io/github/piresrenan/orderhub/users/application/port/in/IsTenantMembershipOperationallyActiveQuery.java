package io.github.piresrenan.orderhub.users.application.port.in;

import java.util.UUID;

/**
 * Carries the complete identity of the exact User/Tenant membership whose
 * operational eligibility is being asked about.
 *
 * <p>
 * The query validates its own structural completeness so an incomplete identity
 * pair never reaches the Users application service or its persistence boundary.
 * </p>
 *
 * @param userId internal User identifier
 * @param tenantId Tenant identifier
 */
public record IsTenantMembershipOperationallyActiveQuery(
        UUID userId,
        UUID tenantId) {

    /**
     * Validates the complete membership identity represented by this query.
     *
     * @throws IllegalArgumentException when either required identifier is missing
     */
    public IsTenantMembershipOperationallyActiveQuery {
        if (userId == null) {
            throw new IllegalArgumentException(
                    "Membership user id is required");
        }

        if (tenantId == null) {
            throw new IllegalArgumentException(
                    "Membership tenant id is required");
        }
    }
}

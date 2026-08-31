package io.github.piresrenan.orderhub.users.application.port.in;

import java.util.UUID;

/**
 * Carries the complete identity required to query one User/Tenant membership.
 *
 * <p>
 * The query validates its own structural completeness so invalid identity pairs
 * never cross the application output boundary.
 * </p>
 *
 * @param userId internal User identifier
 * @param tenantId Tenant identifier
 */
public record FindTenantMembershipQuery(
        UUID userId,
        UUID tenantId) {

    /**
     * Validates the complete membership identity represented by this query.
     *
     * @throws IllegalArgumentException when either required identifier is missing
     */
    public FindTenantMembershipQuery {
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

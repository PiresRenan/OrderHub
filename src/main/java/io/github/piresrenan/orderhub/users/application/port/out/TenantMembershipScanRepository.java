package io.github.piresrenan.orderhub.users.application.port.out;

import java.util.List;
import java.util.UUID;

/**
 * Reads bounded, ordered windows of one User's operationally active memberships.
 */
public interface TenantMembershipScanRepository {

    /**
     * Returns Tenant identifiers of ACTIVE memberships for the User, strictly
     * after the cursor, in ascending order.
     *
     * @param userId internal User identifier
     * @param afterTenantId exclusive cursor, or null for the first window
     * @param maxRows maximum rows to return
     * @return ordered Tenant identifiers
     * @throws TenantMembershipPersistenceException when PostgreSQL access fails
     */
    List<UUID> findOperationallyActiveTenantIds(
            UUID userId,
            UUID afterTenantId,
            int maxRows);
}

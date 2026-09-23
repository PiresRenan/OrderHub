package io.github.piresrenan.orderhub.users.application.port.in;

import java.util.List;
import java.util.UUID;

/**
 * One scanned window of Tenant identifiers for a User's operationally active
 * memberships.
 *
 * <p>
 * The identifiers are membership candidates only. They say nothing about
 * Tenant operational state and never constitute Tenant authority.
 * </p>
 *
 * @param tenantIds scanned Tenant identifiers in ascending order
 * @param hasMore whether active memberships exist beyond the last scanned identifier
 */
public record OperationallyActiveMembershipTenantScan(
        List<UUID> tenantIds,
        boolean hasMore) {

    /** Copies the window so callers cannot mutate scan evidence. */
    public OperationallyActiveMembershipTenantScan {
        tenantIds = List.copyOf(tenantIds);
    }
}

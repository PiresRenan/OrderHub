package io.github.piresrenan.orderhub.users.application.port.out;

import java.util.Optional;
import java.util.UUID;

import io.github.piresrenan.orderhub.users.domain.model.TenantMembership;

public interface TenantMembershipRepository {

    /**
     * Persists one User/Tenant membership association.
     *
     * <p>
     * Pair uniqueness is enforced by the durable persistence boundary because it
     * depends on previously stored memberships.
     * </p>
     *
     * @param membership valid association requested for persistence
     * @return persisted TenantMembership
     */
    TenantMembership save(TenantMembership membership);

    /**
     * Finds the association for one exact User/Tenant identity pair.
     *
     * @param userId internal User identifier
     * @param tenantId Tenant identifier
     * @return matching membership when present, otherwise empty
     */
    Optional<TenantMembership> find(
            UUID userId,
            UUID tenantId);
}

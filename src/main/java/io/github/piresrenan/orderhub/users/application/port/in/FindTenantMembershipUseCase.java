package io.github.piresrenan.orderhub.users.application.port.in;

import java.util.Optional;

import io.github.piresrenan.orderhub.users.domain.model.TenantMembership;

public interface FindTenantMembershipUseCase {

    /**
     * Finds one exact User/Tenant membership association.
     *
     * @param query complete membership identity to locate
     * @return matching membership when present, otherwise empty
     */
    Optional<TenantMembership> find(
            FindTenantMembershipQuery query);
}

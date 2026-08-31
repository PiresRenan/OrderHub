package io.github.piresrenan.orderhub.users.application.port.in;

import io.github.piresrenan.orderhub.users.domain.model.TenantMembership;

public interface EstablishTenantMembershipUseCase {

    /**
     * Establishes one durable association between a User and a Tenant.
     *
     * @param command identities participating in the requested association
     * @return successfully created and persisted TenantMembership
     * @throws IllegalArgumentException when membership domain invariants reject
     *                                  the supplied identities
     */
    TenantMembership establish(
            EstablishTenantMembershipCommand command);
}

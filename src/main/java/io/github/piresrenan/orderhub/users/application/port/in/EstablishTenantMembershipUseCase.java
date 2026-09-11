package io.github.piresrenan.orderhub.users.application.port.in;

/**
 * Defines the application boundary for establishing one durable User/Tenant
 * association.
 */
public interface EstablishTenantMembershipUseCase {

    /**
     * Establishes one durable association between a User and a Tenant.
     *
     * <p>
     * Normal completion means the association was persisted. No result is
     * produced because the command already carries every caller-known
     * identity, and the TenantMembership aggregate remains internal to Users.
     * </p>
     *
     * @param command identities participating in the requested association
     * @throws IllegalArgumentException when membership domain invariants reject
     *                                  the supplied identities
     */
    void establish(
            EstablishTenantMembershipCommand command);
}

package io.github.piresrenan.orderhub.users.application.port.in;

/**
 * Ensures the Users-owned operational desired state for one exact
 * User/Tenant membership.
 *
 * <p>An absent membership may be established. An already operational
 * membership is desired-state success. A durable non-operational membership
 * must never be reactivated implicitly by this boundary.</p>
 */
public interface EnsureActiveTenantMembershipUseCase {

    /**
     * Ensures the exact membership represented by the command is operational
     * without exposing Users lifecycle vocabulary.
     *
     * @param command exact internal User and Tenant identities
     * @return operational success or durable non-operational conflict
     */
    TenantMembershipEnsureResult ensureActive(
            EstablishTenantMembershipCommand command);
}

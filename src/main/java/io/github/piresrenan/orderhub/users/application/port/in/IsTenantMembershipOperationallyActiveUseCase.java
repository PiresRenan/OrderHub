package io.github.piresrenan.orderhub.users.application.port.in;

/**
 * Answers the Users-owned question of whether one exact User/Tenant membership
 * is currently operational.
 *
 * <p>
 * Membership lifecycle vocabulary and the policy that decides which lifecycle
 * states remain operational belong to Users. Consumers receive only the
 * fail-closed predicate so the TenantMembership domain model, its status
 * vocabulary and the Users persistence boundary stay internal.
 * </p>
 */
public interface IsTenantMembershipOperationallyActiveUseCase {

    /**
     * Reports whether the exact User/Tenant relationship may currently
     * participate in operations that require proven membership.
     *
     * @param query complete membership identity to evaluate
     * @return true only when the exact membership exists and Users considers it
     *         operational; false when it is absent or non-operational
     */
    boolean isOperationallyActive(
            IsTenantMembershipOperationallyActiveQuery query);
}

package io.github.piresrenan.orderhub.users.domain.model;

/**
 * Operational lifecycle of one durable User/Tenant membership relationship.
 *
 * <p>
 * Membership status is not a role, permission, authentication credential or
 * Tenant operational state. It answers only whether the relationship itself is
 * currently operational.
 * </p>
 */
public enum TenantMembershipStatus {

    ACTIVE,
    SUSPENDED,
    TERMINATED
}

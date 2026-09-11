package io.github.piresrenan.orderhub.users.application.port.in;

/**
 * Users-owned result of ensuring that one exact User/Tenant membership may
 * participate in new operational Tenant activity.
 *
 * <p>The result deliberately does not expose TenantMembership lifecycle
 * vocabulary. Callers need only know whether the requested desired state is
 * operational or whether an existing non-operational relationship prevents
 * implicit establishment.</p>
 */
public sealed interface TenantMembershipEnsureResult
        permits TenantMembershipEnsureResult.Operational,
                TenantMembershipEnsureResult.NonOperational {

    /**
     * The exact User/Tenant relationship is operational after the request.
     *
     * <p>This outcome intentionally does not reveal whether the membership was
     * established by this request or was already operational.</p>
     */
    record Operational()
            implements TenantMembershipEnsureResult {
    }

    /**
     * A durable relationship exists but Users does not permit it to become
     * operational implicitly.
     */
    record NonOperational()
            implements TenantMembershipEnsureResult {
    }
}

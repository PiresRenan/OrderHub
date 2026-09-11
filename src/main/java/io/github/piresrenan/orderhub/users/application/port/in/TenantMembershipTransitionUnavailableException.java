package io.github.piresrenan.orderhub.users.application.port.in;

public final class TenantMembershipTransitionUnavailableException extends RuntimeException {
    /** Creates the bounded failure classification without exposing private inputs or infrastructure details. */
    public TenantMembershipTransitionUnavailableException() { super("Tenant membership transition is unavailable"); }
}

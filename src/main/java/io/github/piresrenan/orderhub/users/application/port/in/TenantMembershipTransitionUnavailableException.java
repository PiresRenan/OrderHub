package io.github.piresrenan.orderhub.users.application.port.in;

public final class TenantMembershipTransitionUnavailableException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** Creates the bounded failure classification without exposing private inputs or infrastructure details. */
    public TenantMembershipTransitionUnavailableException() { super("Tenant membership transition is unavailable"); }
}

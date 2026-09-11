package io.github.piresrenan.orderhub.users.application.port.in;

public final class TenantMembershipTransitionUnavailableException extends RuntimeException {
    public TenantMembershipTransitionUnavailableException() { super("Tenant membership transition is unavailable"); }
}

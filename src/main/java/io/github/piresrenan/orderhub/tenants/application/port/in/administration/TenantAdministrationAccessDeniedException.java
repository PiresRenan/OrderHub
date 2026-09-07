package io.github.piresrenan.orderhub.tenants.application.port.in.administration;

public final class TenantAdministrationAccessDeniedException extends RuntimeException {
    public TenantAdministrationAccessDeniedException() {
        super("Tenant administration access denied");
    }
}

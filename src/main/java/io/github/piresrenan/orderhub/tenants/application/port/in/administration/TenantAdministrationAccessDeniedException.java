package io.github.piresrenan.orderhub.tenants.application.port.in.administration;

public final class TenantAdministrationAccessDeniedException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public TenantAdministrationAccessDeniedException() {
        super("Tenant administration access denied");
    }
}

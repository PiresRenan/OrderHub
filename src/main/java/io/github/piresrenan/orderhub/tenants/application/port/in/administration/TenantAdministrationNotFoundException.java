package io.github.piresrenan.orderhub.tenants.application.port.in.administration;

public final class TenantAdministrationNotFoundException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public TenantAdministrationNotFoundException() {
        super("Tenant not found");
    }
}

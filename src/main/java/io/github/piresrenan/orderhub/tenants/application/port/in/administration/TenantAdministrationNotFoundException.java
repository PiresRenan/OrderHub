package io.github.piresrenan.orderhub.tenants.application.port.in.administration;

public final class TenantAdministrationNotFoundException extends RuntimeException {
    public TenantAdministrationNotFoundException() {
        super("Tenant not found");
    }
}

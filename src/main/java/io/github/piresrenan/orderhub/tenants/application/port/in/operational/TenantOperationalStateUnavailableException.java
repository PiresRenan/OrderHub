package io.github.piresrenan.orderhub.tenants.application.port.in.operational;

public final class TenantOperationalStateUnavailableException
        extends RuntimeException {
    private static final long serialVersionUID = 1L;


    public TenantOperationalStateUnavailableException(
            Throwable cause) {

        super(
                "Tenant operational state is unavailable.",
                cause);
    }
}

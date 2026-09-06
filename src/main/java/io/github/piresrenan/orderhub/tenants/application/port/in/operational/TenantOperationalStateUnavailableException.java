package io.github.piresrenan.orderhub.tenants.application.port.in.operational;

public final class TenantOperationalStateUnavailableException
        extends RuntimeException {

    public TenantOperationalStateUnavailableException(
            Throwable cause) {

        super(
                "Tenant operational state is unavailable.",
                cause);
    }
}

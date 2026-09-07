package io.github.piresrenan.orderhub.organizations.application.port.in.administration;

public final class OrganizationUnavailableException extends RuntimeException {
    public OrganizationUnavailableException() {
        super("Organization unavailable");
    }
}

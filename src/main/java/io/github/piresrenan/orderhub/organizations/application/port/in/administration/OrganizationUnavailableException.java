package io.github.piresrenan.orderhub.organizations.application.port.in.administration;

public final class OrganizationUnavailableException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public OrganizationUnavailableException() {
        super("Organization unavailable");
    }
}

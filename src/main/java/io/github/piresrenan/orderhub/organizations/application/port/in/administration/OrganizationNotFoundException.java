package io.github.piresrenan.orderhub.organizations.application.port.in.administration;

public final class OrganizationNotFoundException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public OrganizationNotFoundException() {
        super("Organization not found");
    }
}

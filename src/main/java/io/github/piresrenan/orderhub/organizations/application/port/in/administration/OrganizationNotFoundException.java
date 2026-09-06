package io.github.piresrenan.orderhub.organizations.application.port.in.administration;

public final class OrganizationNotFoundException extends RuntimeException {
    public OrganizationNotFoundException() {
        super("Organization not found");
    }
}

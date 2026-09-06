package io.github.piresrenan.orderhub.organizations.application.port.in.administration;

public final class AdministrativeTargetNotFoundException extends RuntimeException {
    public AdministrativeTargetNotFoundException() {
        super("Administrative target not found");
    }
}

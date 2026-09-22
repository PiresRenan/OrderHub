package io.github.piresrenan.orderhub.organizations.application.port.in.administration;

public final class AdministrativeTargetNotFoundException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public AdministrativeTargetNotFoundException() {
        super("Administrative target not found");
    }
}

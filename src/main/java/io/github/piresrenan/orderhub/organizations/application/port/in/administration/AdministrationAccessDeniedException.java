package io.github.piresrenan.orderhub.organizations.application.port.in.administration;

public final class AdministrationAccessDeniedException extends RuntimeException {
    public AdministrationAccessDeniedException() {
        super("Administration access denied");
    }
}

package io.github.piresrenan.orderhub.organizations.application.port.in.administration;

public final class AdministrationAccessDeniedException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public AdministrationAccessDeniedException() {
        super("Administration access denied");
    }
}

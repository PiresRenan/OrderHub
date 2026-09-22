package io.github.piresrenan.orderhub.organizations.application.port.in.administration;

public final class PlatformAdministrationAccessDeniedException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public PlatformAdministrationAccessDeniedException() {
        super("Platform administration access denied");
    }
}

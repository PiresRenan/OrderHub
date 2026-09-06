package io.github.piresrenan.orderhub.organizations.application.port.in.administration;

public final class PlatformAdministrationAccessDeniedException extends RuntimeException {
    public PlatformAdministrationAccessDeniedException() {
        super("Platform administration access denied");
    }
}

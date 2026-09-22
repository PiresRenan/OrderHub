package io.github.piresrenan.orderhub.organizations.application.port.in.administration;

public final class AdministrativeConflictException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    public AdministrativeConflictException() {
        super("Administrative operation conflicts with current state");
    }
}

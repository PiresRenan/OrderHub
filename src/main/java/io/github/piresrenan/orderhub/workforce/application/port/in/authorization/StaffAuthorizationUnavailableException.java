package io.github.piresrenan.orderhub.workforce.application.port.in.authorization;

/** Technical uncertainty is distinct from a policy denial and discloses no authority state. */
public final class StaffAuthorizationUnavailableException extends RuntimeException {

    /** Retains the diagnostic cause internally while publishing only a constant message. */
    public StaffAuthorizationUnavailableException(Throwable cause) {
        super("Staff authorization could not be established", cause);
    }
}

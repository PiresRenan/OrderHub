package io.github.piresrenan.orderhub.users.application.port.in;

public final class ExternalIdentityLifecycleUnavailableException extends RuntimeException {
    /** Creates the bounded failure classification without exposing private inputs or infrastructure details. */
    public ExternalIdentityLifecycleUnavailableException() { super("External identity lifecycle is unavailable"); }
}

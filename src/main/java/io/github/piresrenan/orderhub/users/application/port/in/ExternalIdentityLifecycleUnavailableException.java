package io.github.piresrenan.orderhub.users.application.port.in;

public final class ExternalIdentityLifecycleUnavailableException extends RuntimeException {
    public ExternalIdentityLifecycleUnavailableException() { super("External identity lifecycle is unavailable"); }
}

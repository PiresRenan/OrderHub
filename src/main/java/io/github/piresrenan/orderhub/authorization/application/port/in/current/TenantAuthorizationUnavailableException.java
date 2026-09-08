package io.github.piresrenan.orderhub.authorization.application.port.in.current;

/** Sanitized technical failure for callers requiring a definite current-policy decision. */
public final class TenantAuthorizationUnavailableException extends RuntimeException {

    /** Keeps diagnostics in the cause, never in the public error message. */
    public TenantAuthorizationUnavailableException(Throwable cause) {
        super("Tenant authorization could not be established", cause);
    }
}

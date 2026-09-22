package io.github.piresrenan.orderhub.security.application.port.in;

/** Classifies authenticated User resolution as temporarily unavailable, independent of Users internals. */
public final class AuthenticatedUserResolutionUnavailableException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** Creates the bounded failure classification; the upstream cause stays available for controlled diagnostics. */
    public AuthenticatedUserResolutionUnavailableException(Throwable cause) { super("Authenticated user resolution is unavailable", cause); }
}

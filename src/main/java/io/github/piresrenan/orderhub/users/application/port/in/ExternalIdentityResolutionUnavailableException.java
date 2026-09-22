package io.github.piresrenan.orderhub.users.application.port.in;

/** Classifies external identity resolution as temporarily unavailable without exposing persistence details. */
public final class ExternalIdentityResolutionUnavailableException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** Creates the bounded failure classification; the internal cause stays available for controlled diagnostics. */
    public ExternalIdentityResolutionUnavailableException(Throwable cause) { super("External identity resolution is unavailable", cause); }
}

package io.github.piresrenan.orderhub.users.application.port.in;

/** Classifies a membership scan as technically unavailable without exposing persistence details. */
public final class TenantMembershipScanUnavailableException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** Creates the bounded failure classification; the internal cause stays available for controlled diagnostics. */
    public TenantMembershipScanUnavailableException(Throwable cause) { super("Tenant membership scan is unavailable", cause); }
}

package io.github.piresrenan.orderhub.security.adapter.in.web;

/** Signals an out-of-range discovery parameter without carrying the rejected value. */
final class TenantDiscoveryRequestRejectedException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** Creates the bounded rejection; no request value is retained. */
    TenantDiscoveryRequestRejectedException() { super("Invalid Tenant discovery request"); }
}

package io.github.piresrenan.orderhub.workforce.application.service;

/** Privacy-equivalent rejection of a proof or non-operational desired state. */
public final class StaffProvisioningUnavailableException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** Exposes no secret, target identity or durable terminal state. */
    public StaffProvisioningUnavailableException() {
        super("Staff provisioning is unavailable");
    }
}

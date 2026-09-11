package io.github.piresrenan.orderhub.authorization.application.port.in.provisioning;

/** Bounded policy rejection, distinct from technical authorization uncertainty. */
public final class StaffProvisioningAuthorizationDeniedException extends RuntimeException {
    private static final long serialVersionUID = 1L;
    /** Reveals no actor grant, role existence or permission internals. */
    public StaffProvisioningAuthorizationDeniedException() {
        super("Staff provisioning authority is unavailable");
    }
}

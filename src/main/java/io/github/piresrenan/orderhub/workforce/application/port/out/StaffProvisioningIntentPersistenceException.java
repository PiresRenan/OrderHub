package io.github.piresrenan.orderhub.workforce.application.port.out;

/**
 * Failure while accessing durable Staff provisioning-intent state.
 *
 * <p>The message must remain free of provisioning secrets and digests.</p>
 */
public final class StaffProvisioningIntentPersistenceException
        extends RuntimeException {

    private static final long serialVersionUID = 1L;

    /** Creates the bounded failure classification without exposing private inputs or infrastructure details. */
    public StaffProvisioningIntentPersistenceException(
            String message,
            Throwable cause) {

        super(
                message,
                cause);
    }
}

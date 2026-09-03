package io.github.piresrenan.orderhub.workforce.application.port.out;

/**
 * Workforce-owned persistence failure exposed without leaking adapter-specific
 * data-access exceptions into the application boundary.
 */
public final class WorkforceAuditPersistenceException
        extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public WorkforceAuditPersistenceException(
            String message,
            Throwable cause) {

        super(message, cause);
    }
}

package io.github.piresrenan.orderhub.workforce.application.port.out;

/**
 * Failure while resolving or persisting authoritative workforce position state.
 */
public final class WorkforcePositionChangePersistenceException
        extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public WorkforcePositionChangePersistenceException(
            String message) {

        super(message);
    }

    public WorkforcePositionChangePersistenceException(
            String message,
            Throwable cause) {

        super(message, cause);
    }
}

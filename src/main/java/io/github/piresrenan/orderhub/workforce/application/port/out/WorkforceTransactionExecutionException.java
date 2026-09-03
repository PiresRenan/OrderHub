package io.github.piresrenan.orderhub.workforce.application.port.out;

/**
 * Failure while establishing or completing a workforce transaction boundary.
 */
public final class WorkforceTransactionExecutionException
        extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public WorkforceTransactionExecutionException(
            Throwable cause) {

        super(cause);
    }
}

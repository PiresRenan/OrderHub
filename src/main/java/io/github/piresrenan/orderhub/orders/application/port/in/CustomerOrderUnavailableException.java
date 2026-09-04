package io.github.piresrenan.orderhub.orders.application.port.in;

/**
 * Signals that a Customer self-service Order read cannot return the requested
 * resource without distinguishing absence from failed ownership authorization.
 */
public final class CustomerOrderUnavailableException
        extends RuntimeException {

    public CustomerOrderUnavailableException() {

        super(
                "Customer order is unavailable.");
    }
}

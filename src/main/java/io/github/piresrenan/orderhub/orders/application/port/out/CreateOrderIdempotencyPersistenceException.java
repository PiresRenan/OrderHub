package io.github.piresrenan.orderhub.orders.application.port.out;

/**
 * Signals a technical failure while accessing durable idempotency state.
 */
public final class CreateOrderIdempotencyPersistenceException
        extends RuntimeException {
    private static final long serialVersionUID = 1L;


    public CreateOrderIdempotencyPersistenceException(
            String message) {

        super(
                message);
    }

    public CreateOrderIdempotencyPersistenceException(
            String message,
            Throwable cause) {

        super(
                message,
                cause);
    }
}

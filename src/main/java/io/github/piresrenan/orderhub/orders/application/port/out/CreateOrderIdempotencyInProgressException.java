package io.github.piresrenan.orderhub.orders.application.port.out;

/**
 * Signals that durable idempotency acquisition exceeded its bounded
 * lock-wait policy.
 */
public final class CreateOrderIdempotencyInProgressException
        extends RuntimeException {

    public CreateOrderIdempotencyInProgressException(
            Throwable cause) {

        super(
                "Create-order idempotency acquisition is still in progress.",
                cause);
    }
}

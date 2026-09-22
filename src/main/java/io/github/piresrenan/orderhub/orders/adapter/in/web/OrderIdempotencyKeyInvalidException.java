package io.github.piresrenan.orderhub.orders.adapter.in.web;

/**
 * Signals a privacy-safe violation of the create-Order Idempotency-Key HTTP
 * contract.
 */
final class OrderIdempotencyKeyInvalidException
        extends RuntimeException {
    private static final long serialVersionUID = 1L;


    OrderIdempotencyKeyInvalidException() {

        super(
                "Order idempotency key is missing or invalid");
    }
}

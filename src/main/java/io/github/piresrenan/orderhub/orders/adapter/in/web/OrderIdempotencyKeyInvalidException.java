package io.github.piresrenan.orderhub.orders.adapter.in.web;

/**
 * Signals a privacy-safe violation of the create-Order Idempotency-Key HTTP
 * contract.
 */
final class OrderIdempotencyKeyInvalidException
        extends RuntimeException {

    OrderIdempotencyKeyInvalidException() {

        super(
                "Order idempotency key is missing or invalid");
    }
}

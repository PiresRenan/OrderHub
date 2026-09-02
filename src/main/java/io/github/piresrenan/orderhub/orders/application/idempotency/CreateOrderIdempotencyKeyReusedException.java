package io.github.piresrenan.orderhub.orders.application.idempotency;

/**
 * Signals reuse of one durable create-Order idempotency identity for a
 * different canonical business request.
 *
 * <p>
 * The exception deliberately carries no key, digest, fingerprint or business
 * identifiers.
 * </p>
 */
public final class CreateOrderIdempotencyKeyReusedException
        extends RuntimeException {

    public CreateOrderIdempotencyKeyReusedException() {

        super(
                "Create-order idempotency key was reused for a different request.");
    }
}

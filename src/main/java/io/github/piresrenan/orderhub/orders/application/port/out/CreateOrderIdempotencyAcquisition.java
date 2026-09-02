package io.github.piresrenan.orderhub.orders.application.port.out;

/**
 * Result of attempting to acquire one durable create-Order idempotency
 * identity.
 */
public sealed interface CreateOrderIdempotencyAcquisition
        permits CreateOrderIdempotencyAcquisition.Acquired,
        CreateOrderIdempotencyAcquisition.Replay,
        CreateOrderIdempotencyAcquisition.FingerprintConflict {

    /**
     * The current transaction inserted PROCESSING and owns first execution.
     */
    record Acquired()
            implements CreateOrderIdempotencyAcquisition {
    }

    /**
     * A committed successful execution already owns the identity.
     *
     * @param completion stable replay projection
     */
    record Replay(
            CreateOrderIdempotencyCompletion completion)
            implements CreateOrderIdempotencyAcquisition {

        public Replay {
            if (completion == null) {
                throw new IllegalArgumentException(
                        "Idempotency replay completion is required");
            }
        }
    }

    /**
     * The durable key identity exists for a different canonical request.
     */
    record FingerprintConflict()
            implements CreateOrderIdempotencyAcquisition {
    }
}

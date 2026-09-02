package io.github.piresrenan.orderhub.orders.application.port.out;

import java.util.UUID;

import io.github.piresrenan.orderhub.orders.application.idempotency.CreateOrderRequestFingerprint;
import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderIdempotencyKeyDigest;

/**
 * Durable persistence boundary for create-Order request idempotency.
 *
 * <p>
 * Implementations participate in the transaction already owned by the
 * create-Order application use case. They must not create independent
 * transaction boundaries.
 * </p>
 */
public interface CreateOrderIdempotencyRepository {

    CreateOrderIdempotencyAcquisition acquire(
            UUID tenantId,
            CreateOrderIdempotencyKeyDigest keyDigest,
            CreateOrderRequestFingerprint fingerprint);

    void complete(
            UUID tenantId,
            CreateOrderIdempotencyKeyDigest keyDigest,
            CreateOrderRequestFingerprint fingerprint,
            CreateOrderIdempotencyCompletion completion);
}

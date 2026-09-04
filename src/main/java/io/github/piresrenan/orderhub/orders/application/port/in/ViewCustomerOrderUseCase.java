package io.github.piresrenan.orderhub.orders.application.port.in;

import java.util.UUID;

import io.github.piresrenan.orderhub.orders.domain.model.Order;

/**
 * Customer self-service boundary for viewing one Order only after the
 * authoritative Order ownership relationship is proven.
 */
public interface ViewCustomerOrderUseCase {

    Order view(
            UUID actorUserId,
            UUID tenantId,
            UUID orderId);
}

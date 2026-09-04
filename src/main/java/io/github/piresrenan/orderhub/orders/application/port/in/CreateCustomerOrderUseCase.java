package io.github.piresrenan.orderhub.orders.application.port.in;

import java.util.UUID;

/**
 * Customer self-service boundary for creating an Order only after ownership
 * evidence and Customer authorization are established.
 */
public interface CreateCustomerOrderUseCase {

    CreateOrderResult create(
            UUID actorUserId,
            CreateOrderCommand command);
}

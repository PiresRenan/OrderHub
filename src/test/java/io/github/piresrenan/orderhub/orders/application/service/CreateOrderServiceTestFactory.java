package io.github.piresrenan.orderhub.orders.application.service;

import java.util.UUID;

import io.github.piresrenan.orderhub.catalog.application.port.in.orderability.ValidateOrderableVariantsUseCase;
import io.github.piresrenan.orderhub.inventory.application.port.in.CommitOrderInventoryUseCase;
import io.github.piresrenan.orderhub.orders.application.idempotency.CreateOrderRequestFingerprint;
import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderIdempotencyKeyDigest;
import io.github.piresrenan.orderhub.orders.application.port.out.CreateOrderIdempotencyAcquisition;
import io.github.piresrenan.orderhub.orders.application.port.out.CreateOrderIdempotencyCompletion;
import io.github.piresrenan.orderhub.orders.application.port.out.CreateOrderIdempotencyRepository;
import io.github.piresrenan.orderhub.orders.application.port.out.OrderIdGenerator;
import io.github.piresrenan.orderhub.orders.application.port.out.OrderRepository;
import io.github.piresrenan.orderhub.orders.application.port.out.TransactionExecutor;

final class CreateOrderServiceTestFactory {

    private CreateOrderServiceTestFactory() {
    }

    static CreateOrderService create(
            OrderRepository orderRepository,
            OrderIdGenerator orderIdGenerator,
            TransactionExecutor transactionExecutor,
            ValidateOrderableVariantsUseCase catalog,
            CommitOrderInventoryUseCase inventory) {

        return new CreateOrderService(
                orderRepository,
                orderIdGenerator,
                transactionExecutor,
                catalog,
                inventory,
                new AlwaysAcquiredIdempotencyRepository());
    }

    private static final class AlwaysAcquiredIdempotencyRepository
            implements CreateOrderIdempotencyRepository {

        @Override
        public CreateOrderIdempotencyAcquisition acquire(
                UUID tenantId,
                CreateOrderIdempotencyKeyDigest keyDigest,
                CreateOrderRequestFingerprint fingerprint) {

            return new CreateOrderIdempotencyAcquisition.Acquired();
        }

        @Override
        public void complete(
                UUID tenantId,
                CreateOrderIdempotencyKeyDigest keyDigest,
                CreateOrderRequestFingerprint fingerprint,
                CreateOrderIdempotencyCompletion completion) {

            // Existing tests intentionally focus on non-idempotency behavior.
        }
    }
}

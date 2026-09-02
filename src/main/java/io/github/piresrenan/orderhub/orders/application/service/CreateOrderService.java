package io.github.piresrenan.orderhub.orders.application.service;

import java.util.List;
import java.util.Objects;

import io.github.piresrenan.orderhub.catalog.application.port.in.orderability.ValidateOrderableVariantsCommand;
import io.github.piresrenan.orderhub.catalog.application.port.in.orderability.ValidateOrderableVariantsUseCase;
import io.github.piresrenan.orderhub.inventory.application.port.in.CommitOrderInventoryCommand;
import io.github.piresrenan.orderhub.inventory.application.port.in.CommitOrderInventoryUseCase;
import io.github.piresrenan.orderhub.inventory.application.port.in.InventoryAllocationOutcome;
import io.github.piresrenan.orderhub.orders.application.idempotency.CreateOrderIdempotencyKeyReusedException;
import io.github.piresrenan.orderhub.orders.application.idempotency.CreateOrderRequestFingerprint;
import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderAllocationOutcome;
import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderCommand;
import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderResult;
import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderUseCase;
import io.github.piresrenan.orderhub.orders.application.port.out.CreateOrderIdempotencyAcquisition;
import io.github.piresrenan.orderhub.orders.application.port.out.CreateOrderIdempotencyCompletion;
import io.github.piresrenan.orderhub.orders.application.port.out.CreateOrderIdempotencyRepository;
import io.github.piresrenan.orderhub.orders.application.port.out.OrderIdGenerator;
import io.github.piresrenan.orderhub.orders.application.port.out.OrderRepository;
import io.github.piresrenan.orderhub.orders.application.port.out.TransactionExecutor;
import io.github.piresrenan.orderhub.orders.domain.model.Order;
import io.github.piresrenan.orderhub.orders.domain.model.OrderItem;

/**
 * Coordinates durable idempotency, Order persistence, Catalog orderability and
 * Inventory commitment inside one physical transaction.
 */
public final class CreateOrderService
        implements CreateOrderUseCase {

    private final OrderRepository orderRepository;

    private final OrderIdGenerator orderIdGenerator;

    private final TransactionExecutor transactionExecutor;

    private final ValidateOrderableVariantsUseCase catalog;

    private final CommitOrderInventoryUseCase inventory;

    private final CreateOrderIdempotencyRepository idempotency;

    public CreateOrderService(
            OrderRepository orderRepository,
            OrderIdGenerator orderIdGenerator,
            TransactionExecutor transactionExecutor,
            ValidateOrderableVariantsUseCase catalog,
            CommitOrderInventoryUseCase inventory,
            CreateOrderIdempotencyRepository idempotency) {

        this.orderRepository =
                Objects.requireNonNull(
                        orderRepository,
                        "orderRepository");

        this.orderIdGenerator =
                Objects.requireNonNull(
                        orderIdGenerator,
                        "orderIdGenerator");

        this.transactionExecutor =
                Objects.requireNonNull(
                        transactionExecutor,
                        "transactionExecutor");

        this.catalog =
                Objects.requireNonNull(
                        catalog,
                        "catalog");

        this.inventory =
                Objects.requireNonNull(
                        inventory,
                        "inventory");

        this.idempotency =
                Objects.requireNonNull(
                        idempotency,
                        "idempotency");
    }

    @Override
    public CreateOrderResult create(
            CreateOrderCommand command) {

        Objects.requireNonNull(
                command,
                "command");

        /*
         * Domain-level item validation remains outside the transaction.
         *
         * This preserves the existing rule that structurally invalid Orders do
         * not consume database transactions or durable idempotency identities.
         */
        var items =
                prepareItems(
                        command);

        validateAggregateInputs(
                command,
                items);

        var fingerprint =
                CreateOrderRequestFingerprint.from(
                        command);

        return transactionExecutor.execute(
                () ->
                        executeInsideTransaction(
                                command,
                                items,
                                fingerprint));
    }

    private CreateOrderResult executeInsideTransaction(
            CreateOrderCommand command,
            List<OrderItem> items,
            CreateOrderRequestFingerprint fingerprint) {

        var acquisition =
                Objects.requireNonNull(
                        idempotency.acquire(
                                command.tenantId(),
                                command.idempotencyKeyDigest(),
                                fingerprint),
                        "idempotency acquisition");

        return switch (acquisition) {

            case CreateOrderIdempotencyAcquisition.Acquired ignored ->
                    executeFirstAttempt(
                            command,
                            items,
                            fingerprint);

            case CreateOrderIdempotencyAcquisition.Replay replay ->
                    replay(
                            command,
                            items,
                            replay.completion());

            case CreateOrderIdempotencyAcquisition.FingerprintConflict ignored ->
                    throw new CreateOrderIdempotencyKeyReusedException();
        };
    }

    private CreateOrderResult executeFirstAttempt(
            CreateOrderCommand command,
            List<OrderItem> items,
            CreateOrderRequestFingerprint fingerprint) {

        /*
         * The durable idempotency identity is already owned at this point.
         *
         * An Order ID is therefore generated only for the transaction that won
         * first execution.
         */
        var order =
                Order.create(
                        orderIdGenerator.generate(),
                        command.tenantId(),
                        command.customerId(),
                        items);

        var persistedOrder =
                orderRepository.save(
                        order);

        /*
         * Catalog locks remain before Inventory mutation, preserving the
         * global OH-011 lock ordering.
         */
        catalog.validate(
                new ValidateOrderableVariantsCommand(
                        persistedOrder.tenantId(),
                        persistedOrder.items()
                                .stream()
                                .map(
                                        OrderItem::variantId)
                                .toList()));

        var inventoryOutcome =
                inventory.commit(
                        new CommitOrderInventoryCommand(
                                persistedOrder.tenantId(),
                                persistedOrder.id(),
                                persistedOrder.items()
                                        .stream()
                                        .map(item ->
                                                new CommitOrderInventoryCommand.Demand(
                                                        item.variantId(),
                                                        item.quantity()))
                                        .toList()));

        var allocationOutcome =
                mapAllocationOutcome(
                        inventoryOutcome);

        var completion =
                new CreateOrderIdempotencyCompletion(
                        persistedOrder.id(),
                        persistedOrder.status(),
                        allocationOutcome);

        /*
         * Completion is written last but still inside the same physical
         * transaction as Order/Catalog/Inventory effects.
         *
         * Any failure before commit therefore removes PROCESSING together with
         * all business effects.
         */
        idempotency.complete(
                command.tenantId(),
                command.idempotencyKeyDigest(),
                fingerprint,
                completion);

        return new CreateOrderResult(
                persistedOrder,
                allocationOutcome);
    }

    private static CreateOrderResult replay(
            CreateOrderCommand command,
            List<OrderItem> items,
            CreateOrderIdempotencyCompletion completion) {

        /*
         * Fingerprint equality has already proven that the business request is
         * the same. Tenant, Customer and item projection can therefore be
         * reconstructed from the current command while durable outcome fields
         * come from the completed idempotency record.
         */
        var order =
                Order.rehydrate(
                        completion.orderId(),
                        command.tenantId(),
                        command.customerId(),
                        items,
                        completion.orderStatus());

        return new CreateOrderResult(
                order,
                completion.allocationOutcome());
    }

    private static List<OrderItem> prepareItems(
            CreateOrderCommand command) {

        var commandItems =
                Objects.requireNonNull(
                        command.items(),
                        "command.items");

        return commandItems
                .stream()
                .map(item -> {

                    if (item == null) {
                        throw new IllegalArgumentException(
                                "Order items must not contain null values");
                    }

                    return new OrderItem(
                            item.variantId(),
                            item.quantity());
                })
                .toList();
    }

    private static void validateAggregateInputs(
            CreateOrderCommand command,
            List<OrderItem> items) {

        if (command.tenantId() == null) {
            throw new IllegalArgumentException(
                    "Tenant id is required");
        }

        if (command.customerId() == null) {
            throw new IllegalArgumentException(
                    "Customer id is required");
        }

        if (items.isEmpty()) {
            throw new IllegalArgumentException(
                    "Order must contain at least one item");
        }
    }

    private static CreateOrderAllocationOutcome mapAllocationOutcome(
            InventoryAllocationOutcome inventoryOutcome) {

        if (inventoryOutcome == null) {

            throw new IllegalStateException(
                    "Inventory allocation outcome is required");
        }

        return switch (inventoryOutcome) {
            case FULLY_ALLOCATED ->
                    CreateOrderAllocationOutcome.FULLY_ALLOCATED;
            case PARTIALLY_BACKORDERED ->
                    CreateOrderAllocationOutcome.PARTIALLY_BACKORDERED;
            case FULLY_BACKORDERED ->
                    CreateOrderAllocationOutcome.FULLY_BACKORDERED;
        };
    }
}

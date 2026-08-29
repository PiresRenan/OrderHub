package io.github.piresrenan.orderhub.orders.domain.model;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

public final class Order {

    private final UUID id;
    private final UUID tenantId;
    private final UUID customerId;
    private final List<OrderItem> items;
    private final OrderStatus status;

    /**
     * Builds an already validated aggregate and protects its item collection from
     * external mutation.
     */
    private Order(
            UUID id,
            UUID tenantId,
            UUID customerId,
            List<OrderItem> items,
            OrderStatus status) {

        this.id = id;
        this.tenantId = tenantId;
        this.customerId = customerId;
        this.items = List.copyOf(items);
        this.status = status;
    }

    /**
     * Creates a new order while enforcing all invariants required for an aggregate
     * to exist in CREATED state.
     *
     * <p>The caller cannot choose the initial lifecycle state. New Orders always
     * begin in {@link OrderStatus#CREATED}.</p>
     *
     * @return a valid immutable order aggregate
     * @throws IllegalArgumentException when any required invariant is violated
     */
    public static Order create(
            UUID id,
            UUID tenantId,
            UUID customerId,
            List<OrderItem> items) {

        validateAggregateState(
                id,
                tenantId,
                customerId,
                items);

        return new Order(
                id,
                tenantId,
                customerId,
                items,
                OrderStatus.CREATED);
    }

    /**
     * Reconstructs an existing Order from already persisted state.
     *
     * <p>Unlike {@link #create(UUID, UUID, UUID, List)}, this operation does not
     * represent creation of a new business Order. The lifecycle status is therefore
     * supplied by persistence and preserved during reconstruction.</p>
     *
     * <p>Rehydration still enforces aggregate invariants so corrupted or incomplete
     * persisted state cannot silently enter the domain model.</p>
     *
     * @param id persisted aggregate identifier
     * @param tenantId persisted tenant owner
     * @param customerId persisted customer association
     * @param items persisted items owned by the aggregate
     * @param status persisted lifecycle status
     * @return a valid immutable aggregate reconstructed from persisted state
     * @throws IllegalArgumentException when persisted state violates a domain
     *                                  invariant
     */
    public static Order rehydrate(
            UUID id,
            UUID tenantId,
            UUID customerId,
            List<OrderItem> items,
            OrderStatus status) {

        validateAggregateState(
                id,
                tenantId,
                customerId,
                items);

        if (status == null) {
            throw new IllegalArgumentException(
                    "Order status is required");
        }

        return new Order(
                id,
                tenantId,
                customerId,
                items,
                status);
    }

    /**
     * Verifies structural invariants shared by creation and persistence
     * reconstruction.
     *
     * <p>Lifecycle-specific decisions remain outside this method: creation chooses
     * the initial status while rehydration restores an existing persisted status.</p>
     *
     * @param id aggregate identifier
     * @param tenantId tenant owner
     * @param customerId customer association
     * @param items items owned by the aggregate
     * @throws IllegalArgumentException when required aggregate state is invalid
     */
    private static void validateAggregateState(
            UUID id,
            UUID tenantId,
            UUID customerId,
            List<OrderItem> items) {

        if (id == null) {
            throw new IllegalArgumentException(
                    "Order id is required");
        }

        if (tenantId == null) {
            throw new IllegalArgumentException(
                    "Tenant id is required");
        }

        if (customerId == null) {
            throw new IllegalArgumentException(
                    "Customer id is required");
        }

        if (items == null || items.isEmpty()) {
            throw new IllegalArgumentException(
                    "Order must contain at least one item");
        }

        if (items.stream().anyMatch(Objects::isNull)) {
            throw new IllegalArgumentException(
                    "Order items must not contain null values");
        }
    }

    /**
     * Returns the immutable identity of this order.
     */
    public UUID id() {
        return id;
    }

    /**
     * Returns the tenant to which this aggregate belongs.
     */
    public UUID tenantId() {
        return tenantId;
    }

    /**
     * Returns the customer associated with this order.
     */
    public UUID customerId() {
        return customerId;
    }

    /**
     * Returns an immutable view of the items owned by this aggregate.
     */
    public List<OrderItem> items() {
        return items;
    }

    /**
     * Returns the current lifecycle status of the order.
     */
    public OrderStatus status() {
        return status;
    }
}
package io.github.piresrenan.orderhub.inventory.domain.model;

import java.util.UUID;

/**
 * Immutable inventory state for one sellable Variant inside one Tenant.
 *
 * <p>
 * Physical inventory and accepted uncovered demand remain distinct:
 * {@code committed} represents units physically allocated to accepted demand,
 * while {@code backordered} represents accepted demand without physical
 * coverage.
 * </p>
 *
 * <p>
 * Available-to-promise is intentionally preserved as the raw business value:
 * </p>
 *
 * <pre>
 * onHand - committed - safetyStock
 * </pre>
 *
 * <p>
 * It may therefore be negative. Allocation logic separately clamps the amount
 * that can actually be allocated to zero.
 * </p>
 */
public final class InventoryPosition {

    private final UUID tenantId;
    private final UUID variantId;
    private final long onHand;
    private final long committed;
    private final long backordered;
    private final long safetyStock;

    private InventoryPosition(
            UUID tenantId,
            UUID variantId,
            long onHand,
            long committed,
            long backordered,
            long safetyStock) {

        validate(
                tenantId,
                variantId,
                onHand,
                committed,
                backordered,
                safetyStock);

        this.tenantId = tenantId;
        this.variantId = variantId;
        this.onHand = onHand;
        this.committed = committed;
        this.backordered = backordered;
        this.safetyStock = safetyStock;
    }

    /**
     * Creates a validated inventory state.
     */
    public static InventoryPosition create(
            UUID tenantId,
            UUID variantId,
            long onHand,
            long committed,
            long backordered,
            long safetyStock) {

        return new InventoryPosition(
                tenantId,
                variantId,
                onHand,
                committed,
                backordered,
                safetyStock);
    }

    public UUID tenantId() {
        return tenantId;
    }

    public UUID variantId() {
        return variantId;
    }

    public long onHand() {
        return onHand;
    }

    public long committed() {
        return committed;
    }

    public long backordered() {
        return backordered;
    }

    public long safetyStock() {
        return safetyStock;
    }

    /**
     * Returns the raw available-to-promise quantity.
     *
     * <p>
     * A negative value is meaningful: safety stock already exceeds the
     * uncommitted physical quantity.
     * </p>
     */
    public long availableToPromise() {

        return onHand
                - committed
                - safetyStock;
    }

    /**
     * Applies accepted demand according to the supplied Tenant inventory policy.
     *
     * <p>
     * The current object is never mutated. Successful commitment returns a new
     * InventoryPosition inside the InventoryAllocation result.
     * </p>
     */
    public InventoryAllocation commit(
            long requestedQuantity,
            InventoryPolicy policy) {

        if (requestedQuantity <= 0) {
            throw new IllegalArgumentException(
                    "Inventory requested quantity must be greater than zero");
        }

        if (policy == null) {
            throw new IllegalArgumentException(
                    "Inventory policy is required");
        }

        var allocableQuantity =
                Math.max(
                        0L,
                        availableToPromise());

        if (policy == InventoryPolicy.DENY) {
            return commitDeny(
                    requestedQuantity,
                    allocableQuantity);
        }

        return commitAllowBackorder(
                requestedQuantity,
                allocableQuantity);
    }

    private InventoryAllocation commitDeny(
            long requestedQuantity,
            long allocableQuantity) {

        if (requestedQuantity > allocableQuantity) {
            throw new InsufficientInventoryException();
        }

        var newCommitted =
                Math.addExact(
                        committed,
                        requestedQuantity);

        var newPosition =
                new InventoryPosition(
                        tenantId,
                        variantId,
                        onHand,
                        newCommitted,
                        backordered,
                        safetyStock);

        return new InventoryAllocation(
                newPosition,
                requestedQuantity,
                requestedQuantity,
                0L);
    }

    private InventoryAllocation commitAllowBackorder(
            long requestedQuantity,
            long allocableQuantity) {

        var allocatedQuantity =
                Math.min(
                        requestedQuantity,
                        allocableQuantity);

        var newBackorderedDemand =
                requestedQuantity
                        - allocatedQuantity;

        var newCommitted =
                Math.addExact(
                        committed,
                        allocatedQuantity);

        final long newBackordered;

        try {
            newBackordered =
                    Math.addExact(
                            backordered,
                            newBackorderedDemand);
        } catch (ArithmeticException exception) {
            throw new IllegalArgumentException(
                    "Inventory backordered quantity exceeds supported range",
                    exception);
        }

        var newPosition =
                new InventoryPosition(
                        tenantId,
                        variantId,
                        onHand,
                        newCommitted,
                        newBackordered,
                        safetyStock);

        return new InventoryAllocation(
                newPosition,
                requestedQuantity,
                allocatedQuantity,
                newBackorderedDemand);
    }

    private static void validate(
            UUID tenantId,
            UUID variantId,
            long onHand,
            long committed,
            long backordered,
            long safetyStock) {

        if (tenantId == null) {
            throw new IllegalArgumentException(
                    "Inventory tenant id is required");
        }

        if (variantId == null) {
            throw new IllegalArgumentException(
                    "Inventory variant id is required");
        }

        if (onHand < 0) {
            throw new IllegalArgumentException(
                    "Inventory on-hand quantity must not be negative");
        }

        if (committed < 0) {
            throw new IllegalArgumentException(
                    "Inventory committed quantity must not be negative");
        }

        if (backordered < 0) {
            throw new IllegalArgumentException(
                    "Inventory backordered quantity must not be negative");
        }

        if (safetyStock < 0) {
            throw new IllegalArgumentException(
                    "Inventory safety stock must not be negative");
        }

        if (committed > onHand) {
            throw new IllegalArgumentException(
                    "Inventory committed quantity must not exceed on-hand quantity");
        }
    }
}
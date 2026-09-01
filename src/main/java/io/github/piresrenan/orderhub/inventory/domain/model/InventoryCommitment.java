package io.github.piresrenan.orderhub.inventory.domain.model;

import java.time.Instant;
import java.util.UUID;

/**
 * Immutable durable business fact describing inventory demand accepted for one
 * Order Variant.
 *
 * <p>
 * Every commitment reconciles exactly:
 * </p>
 *
 * <pre>
 * requestedQuantity = allocatedQuantity + backorderedQuantity
 * </pre>
 */
public final class InventoryCommitment {

    private final UUID commitmentId;
    private final UUID tenantId;
    private final UUID orderId;
    private final UUID variantId;
    private final long requestedQuantity;
    private final long allocatedQuantity;
    private final long backorderedQuantity;
    private final Instant createdAt;

    private InventoryCommitment(
            UUID commitmentId,
            UUID tenantId,
            UUID orderId,
            UUID variantId,
            long requestedQuantity,
            long allocatedQuantity,
            long backorderedQuantity,
            Instant createdAt) {

        validate(
                commitmentId,
                tenantId,
                orderId,
                variantId,
                requestedQuantity,
                allocatedQuantity,
                backorderedQuantity,
                createdAt);

        this.commitmentId = commitmentId;
        this.tenantId = tenantId;
        this.orderId = orderId;
        this.variantId = variantId;
        this.requestedQuantity = requestedQuantity;
        this.allocatedQuantity = allocatedQuantity;
        this.backorderedQuantity = backorderedQuantity;
        this.createdAt = createdAt;
    }

    public static InventoryCommitment create(
            UUID commitmentId,
            UUID tenantId,
            UUID orderId,
            UUID variantId,
            long requestedQuantity,
            long allocatedQuantity,
            long backorderedQuantity,
            Instant createdAt) {

        return new InventoryCommitment(
                commitmentId,
                tenantId,
                orderId,
                variantId,
                requestedQuantity,
                allocatedQuantity,
                backorderedQuantity,
                createdAt);
    }

    public UUID commitmentId() {
        return commitmentId;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public UUID orderId() {
        return orderId;
    }

    public UUID variantId() {
        return variantId;
    }

    public long requestedQuantity() {
        return requestedQuantity;
    }

    public long allocatedQuantity() {
        return allocatedQuantity;
    }

    public long backorderedQuantity() {
        return backorderedQuantity;
    }

    public Instant createdAt() {
        return createdAt;
    }

    private static void validate(
            UUID commitmentId,
            UUID tenantId,
            UUID orderId,
            UUID variantId,
            long requestedQuantity,
            long allocatedQuantity,
            long backorderedQuantity,
            Instant createdAt) {

        if (commitmentId == null) {
            throw new IllegalArgumentException(
                    "Inventory commitment id is required");
        }

        if (tenantId == null) {
            throw new IllegalArgumentException(
                    "Inventory commitment tenant id is required");
        }

        if (orderId == null) {
            throw new IllegalArgumentException(
                    "Inventory commitment order id is required");
        }

        if (variantId == null) {
            throw new IllegalArgumentException(
                    "Inventory commitment variant id is required");
        }

        if (requestedQuantity <= 0) {
            throw new IllegalArgumentException(
                    "Inventory commitment requested quantity must be greater than zero");
        }

        if (allocatedQuantity < 0) {
            throw new IllegalArgumentException(
                    "Inventory commitment allocated quantity must not be negative");
        }

        if (backorderedQuantity < 0) {
            throw new IllegalArgumentException(
                    "Inventory commitment backordered quantity must not be negative");
        }

        /*
         * Avoid allocated + backordered overflow while proving the same
         * reconciliation invariant.
         */
        if (allocatedQuantity > requestedQuantity
                || backorderedQuantity > requestedQuantity
                || allocatedQuantity
                        != requestedQuantity - backorderedQuantity) {

            throw new IllegalArgumentException(
                    "Inventory commitment quantities must satisfy requested = allocated + backordered");
        }

        if (createdAt == null) {
            throw new IllegalArgumentException(
                    "Inventory commitment creation time is required");
        }
    }
}
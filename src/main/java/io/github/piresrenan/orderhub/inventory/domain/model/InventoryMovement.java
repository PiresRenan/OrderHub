package io.github.piresrenan.orderhub.inventory.domain.model;

import java.time.Instant;
import java.util.UUID;

/** Immutable receipt/correction fact; its delta is sufficient operational evidence. */
public record InventoryMovement(UUID tenantId, UUID operationId, UUID actorUserId,
        UUID variantId, InventoryMovementType type, long delta, String reason,
        UUID correlationId, Instant occurredAt) {
    /** Validates bounded immutable evidence and normalizes timestamp precision for exact replay. */
    public InventoryMovement {
        java.util.Objects.requireNonNull(tenantId, "tenantId");
        java.util.Objects.requireNonNull(operationId, "operationId");
        java.util.Objects.requireNonNull(actorUserId, "actorUserId");
        java.util.Objects.requireNonNull(variantId, "variantId");
        java.util.Objects.requireNonNull(type, "type");
        java.util.Objects.requireNonNull(correlationId, "correlationId");
        java.util.Objects.requireNonNull(occurredAt, "occurredAt");
        if (delta == 0 || delta == Long.MIN_VALUE || (type == InventoryMovementType.RECEIPT && delta < 0)) {
            throw new IllegalArgumentException("Inventory movement delta is invalid");
        }
        if (reason == null || !reason.matches("[A-Z][A-Z0-9_]{0,63}")) {
            throw new IllegalArgumentException("Inventory movement reason must be a bounded reason code");
        }
        occurredAt = occurredAt.truncatedTo(java.time.temporal.ChronoUnit.MICROS);
    }
}

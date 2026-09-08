package io.github.piresrenan.orderhub.inventory.application.port.in;

import java.util.UUID;
import io.github.piresrenan.orderhub.inventory.domain.model.InventoryMovementType;

/** Trusted actor/Tenant with a client operation identity and bounded stock intent. */
public record InventoryMovementCommand(UUID actorUserId, UUID tenantId, UUID operationId,
        UUID variantId, InventoryMovementType type, long delta, String reason, UUID correlationId) { }

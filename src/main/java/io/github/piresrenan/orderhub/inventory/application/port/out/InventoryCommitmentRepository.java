package io.github.piresrenan.orderhub.inventory.application.port.out;

import java.util.Optional;
import java.util.UUID;

import io.github.piresrenan.orderhub.inventory.domain.model.InventoryCommitment;

/**
 * Output boundary for durable Inventory commitment facts.
 */
public interface InventoryCommitmentRepository {

    InventoryCommitment save(
            InventoryCommitment commitment);

    Optional<InventoryCommitment> findByOrderAndVariant(
            UUID tenantId,
            UUID orderId,
            UUID variantId);
}
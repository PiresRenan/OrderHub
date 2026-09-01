package io.github.piresrenan.orderhub.inventory.application.port.out;

import java.util.Optional;
import java.util.UUID;

import io.github.piresrenan.orderhub.inventory.domain.model.InventoryAllocation;
import io.github.piresrenan.orderhub.inventory.domain.model.InventoryPolicy;
import io.github.piresrenan.orderhub.inventory.domain.model.InventoryPosition;

/**
 * Output boundary for tenant-scoped InventoryPosition persistence and atomic
 * demand commitment.
 *
 * <p>
 * Implementations must preserve correctness across independent application
 * processes sharing the same durable inventory state.
 * </p>
 */
public interface InventoryPositionRepository {

    Optional<InventoryPosition> findById(
            UUID tenantId,
            UUID variantId);

    InventoryAllocation commit(
            UUID tenantId,
            UUID variantId,
            long requestedQuantity,
            InventoryPolicy policy);
}
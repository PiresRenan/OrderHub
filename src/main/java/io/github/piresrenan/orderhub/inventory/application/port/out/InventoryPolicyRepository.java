package io.github.piresrenan.orderhub.inventory.application.port.out;

import java.util.Optional;
import java.util.UUID;

import io.github.piresrenan.orderhub.inventory.domain.model.InventoryPolicy;

/**
 * Output boundary for tenant-scoped Inventory policy lookup.
 *
 * <p>
 * Absence is represented explicitly. The application coordinator decides
 * whether an absent policy must fail closed for a particular use case.
 * </p>
 */
public interface InventoryPolicyRepository {

    Optional<InventoryPolicy> findByTenantId(
            UUID tenantId);
}
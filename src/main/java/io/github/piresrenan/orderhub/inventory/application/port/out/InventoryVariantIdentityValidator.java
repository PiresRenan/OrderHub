package io.github.piresrenan.orderhub.inventory.application.port.out;

import java.util.UUID;

/** Stabilizes exact Tenant/Variant identity inside the calling physical transaction. */
@FunctionalInterface
public interface InventoryVariantIdentityValidator {
    void validate(UUID tenantId, UUID variantId);
}

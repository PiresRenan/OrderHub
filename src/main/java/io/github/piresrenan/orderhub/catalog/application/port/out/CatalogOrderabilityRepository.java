package io.github.piresrenan.orderhub.catalog.application.port.out;

import java.util.Optional;
import java.util.UUID;

/**
 * Acquires PostgreSQL-backed Catalog orderability locks without exposing
 * persistence details to the application service.
 */
public interface CatalogOrderabilityRepository {

    Optional<UUID> lockActiveVariantProductId(
            UUID tenantId,
            UUID variantId);

    boolean lockActiveProduct(
            UUID tenantId,
            UUID productId);
}

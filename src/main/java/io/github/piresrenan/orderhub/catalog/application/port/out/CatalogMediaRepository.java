package io.github.piresrenan.orderhub.catalog.application.port.out;

import java.util.List;
import java.util.UUID;

import io.github.piresrenan.orderhub.catalog.domain.model.CatalogMedia;

/**
 * Persistence boundary for Catalog media metadata.
 */
public interface CatalogMediaRepository {

    CatalogMedia save(
            CatalogMedia media);

    List<CatalogMedia> findByProduct(
            UUID tenantId,
            UUID productId);

    List<CatalogMedia> findByVariant(
            UUID tenantId,
            UUID variantId);
}
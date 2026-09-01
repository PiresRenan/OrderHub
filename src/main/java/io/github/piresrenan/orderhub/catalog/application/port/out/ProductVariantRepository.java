package io.github.piresrenan.orderhub.catalog.application.port.out;

import java.util.Optional;
import java.util.UUID;

import io.github.piresrenan.orderhub.catalog.domain.model.ProductVariant;

/**
 * Persistence boundary for independently addressable sellable Variants.
 */
public interface ProductVariantRepository {

    ProductVariant save(
            ProductVariant variant);

    Optional<ProductVariant> findById(
            UUID tenantId,
            UUID variantId);
}
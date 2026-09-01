package io.github.piresrenan.orderhub.catalog.application.port.out;

import java.util.Optional;
import java.util.UUID;

import io.github.piresrenan.orderhub.catalog.domain.model.VariantBasePrice;

/**
 * Persistence boundary for tenant-scoped Variant base prices.
 */
public interface VariantBasePriceRepository {

    VariantBasePrice save(
            VariantBasePrice basePrice);

    Optional<VariantBasePrice> findByVariantAndCurrency(
            UUID tenantId,
            UUID variantId,
            String currencyCode);
}
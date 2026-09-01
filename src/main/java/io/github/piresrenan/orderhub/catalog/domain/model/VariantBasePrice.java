package io.github.piresrenan.orderhub.catalog.domain.model;

import java.util.UUID;

/**
 * Exact base price for one tenant-owned ProductVariant and currency.
 */
public record VariantBasePrice(
        UUID tenantId,
        UUID variantId,
        Money money) {

    public VariantBasePrice {

        if (tenantId == null) {
            throw new IllegalArgumentException(
                    "Base price tenant id is required");
        }

        if (variantId == null) {
            throw new IllegalArgumentException(
                    "Base price variant id is required");
        }

        if (money == null) {
            throw new IllegalArgumentException(
                    "Base price money is required");
        }
    }

    public static VariantBasePrice create(
            UUID tenantId,
            UUID variantId,
            Money money) {

        return new VariantBasePrice(
                tenantId,
                variantId,
                money);
    }

    public String currencyCode() {
        return money.currencyCode();
    }

    public long minorUnits() {
        return money.minorUnits();
    }
}
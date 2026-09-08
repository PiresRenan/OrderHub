package io.github.piresrenan.orderhub.catalog.application.port.in.administration;

import java.util.List;
import io.github.piresrenan.orderhub.catalog.domain.model.ProductVariantAttribute;

/** Bounded authoring data, excluding ownership and lifecycle. */
public record CatalogVariantMetadata(String sku, String displayName, String gtin, String mpn,
        List<ProductVariantAttribute> attributes) {
    /** Bounds aggregate input while retaining the existing domain's commercial validation. */
    public CatalogVariantMetadata {
        if (sku == null || sku.length() > 160 || attributes == null || attributes.size() > 50) {
            throw new IllegalArgumentException("Invalid Variant metadata");
        }
        attributes = List.copyOf(attributes);
    }
}

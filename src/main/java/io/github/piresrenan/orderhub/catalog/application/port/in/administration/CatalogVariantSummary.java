package io.github.piresrenan.orderhub.catalog.application.port.in.administration;
import java.util.UUID;
import io.github.piresrenan.orderhub.catalog.domain.model.ProductVariantStatus;
/** Variant discovery avoids materializing every attribute on a Product listing. */
public record CatalogVariantSummary(UUID id,UUID productId,String sku,String displayName,ProductVariantStatus status,long revision) {}

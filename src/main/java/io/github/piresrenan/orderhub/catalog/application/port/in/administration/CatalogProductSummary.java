package io.github.piresrenan.orderhub.catalog.application.port.in.administration;
import java.util.UUID;
import io.github.piresrenan.orderhub.catalog.domain.model.ProductStatus;
/** Bounded discovery projection; aggregate details have a separate authorized read. */
public record CatalogProductSummary(UUID id,String name,String slug,ProductStatus status,long revision) {}

package io.github.piresrenan.orderhub.catalog.application.port.out;
import io.github.piresrenan.orderhub.catalog.application.port.in.administration.CatalogAdminContext;
/** Resolves current Staff authority before any sensitive Catalog lookup. */
@FunctionalInterface
public interface CatalogAdminAuthorizer {
    /** Rejects absent current Staff authority before any sensitive Catalog lookup. */
    void require(CatalogAdminContext actor, CatalogAdminPermission permission);
}

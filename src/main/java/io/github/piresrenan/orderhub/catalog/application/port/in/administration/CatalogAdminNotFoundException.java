package io.github.piresrenan.orderhub.catalog.application.port.in.administration;
/** Stable sanitized administrative notfound result. */
public final class CatalogAdminNotFoundException extends RuntimeException {
    /** Keeps missing and foreign resources indistinguishable. */
    public CatalogAdminNotFoundException() { super("Catalog resource is unavailable."); }
}

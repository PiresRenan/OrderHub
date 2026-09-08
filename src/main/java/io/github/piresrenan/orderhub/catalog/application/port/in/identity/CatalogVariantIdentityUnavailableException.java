package io.github.piresrenan.orderhub.catalog.application.port.in.identity;
/** Technical inability to stabilize identity fails closed. */
public final class CatalogVariantIdentityUnavailableException extends RuntimeException {
    /** Exposes only a constant public diagnostic. */
    public CatalogVariantIdentityUnavailableException() { super("Catalog identity could not be established"); }
}

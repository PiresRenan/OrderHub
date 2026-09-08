package io.github.piresrenan.orderhub.catalog.application.port.in.identity;
/** Missing and foreign Variant identities are deliberately indistinguishable. */
public final class CatalogVariantIdentityRejectedException extends RuntimeException {
    /** Publishes no identity existence or ownership detail. */
    public CatalogVariantIdentityRejectedException() { super("Catalog identity is unavailable"); }
}

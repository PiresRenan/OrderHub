package io.github.piresrenan.orderhub.catalog.application.port.in.orderability;

/**
 * Stable non-enumerating rejection for Catalog identities that cannot
 * participate in a new Order.
 */
public final class CatalogOrderabilityRejectedException
        extends RuntimeException {

    public CatalogOrderabilityRejectedException() {

        super(
                "One or more Order items are unavailable.");
    }
}

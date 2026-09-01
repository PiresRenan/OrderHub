package io.github.piresrenan.orderhub.catalog.application.port.in.orderability;

/**
 * Stable technical failure exposed by the Catalog orderability API when
 * commercial eligibility cannot be evaluated because an internal Catalog
 * operation failed.
 *
 * <p>
 * Persistence-specific exception types remain internal to Catalog. Callers
 * receive this API-level failure without SQL, database technology or business
 * identifiers being exposed.
 * </p>
 */
public final class CatalogOrderabilityTechnicalException
        extends RuntimeException {

    public CatalogOrderabilityTechnicalException(
            Throwable cause) {

        super(
                "Catalog orderability validation could not be completed.",
                cause);
    }
}

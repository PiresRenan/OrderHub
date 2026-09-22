package io.github.piresrenan.orderhub.catalog.application.port.in.administration;
/** Stable sanitized administrative unavailable result. */
public final class CatalogAdminUnavailableException extends RuntimeException {
    private static final long serialVersionUID = 1L;

    /** Exposes no diagnostic cause through its public message. */
    public CatalogAdminUnavailableException() { super("Catalog operation could not be completed."); }
}

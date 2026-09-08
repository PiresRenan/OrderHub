package io.github.piresrenan.orderhub.catalog.application.port.in.administration;
/** Stable sanitized administrative denied result. */
public final class CatalogAdminDeniedException extends RuntimeException {
    /** Publishes a constant denial without permission-state disclosure. */
    public CatalogAdminDeniedException() { super("Catalog action is not permitted."); }
}

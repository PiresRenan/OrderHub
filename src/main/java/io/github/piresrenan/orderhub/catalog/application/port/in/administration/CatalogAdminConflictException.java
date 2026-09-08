package io.github.piresrenan.orderhub.catalog.application.port.in.administration;
/** Stable sanitized administrative conflict result. */
public final class CatalogAdminConflictException extends RuntimeException {
    /** Publishes a constant message without merchant keys or persistence details. */
    public CatalogAdminConflictException() { super("Catalog command conflicts with current state."); }
}

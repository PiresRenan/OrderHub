package io.github.piresrenan.orderhub.catalog.application.port.out;

/**
 * Represents a technical failure while a Catalog repository attempts to
 * complete a persistence operation.
 *
 * <p>
 * Infrastructure-specific exception types remain behind the Catalog output
 * boundary. The original cause is retained for internal diagnostics while the
 * public message deliberately exposes no SQL, identifiers or database details.
 * </p>
 */
public final class CatalogPersistenceException extends RuntimeException {

    public CatalogPersistenceException(
            Throwable cause) {

        super(
                "Catalog persistence operation failed.",
                cause);
    }
}
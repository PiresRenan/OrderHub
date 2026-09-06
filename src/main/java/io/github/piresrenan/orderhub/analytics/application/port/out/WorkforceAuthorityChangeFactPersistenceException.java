package io.github.piresrenan.orderhub.analytics.application.port.out;

/**
 * Analytics-owned runtime persistence failure for workforce authority-change
 * facts.
 *
 * <p>
 * The type covers both a semantic identity conflict and an underlying
 * data-access failure, so adapter-specific persistence exceptions never cross
 * the application boundary.
 * </p>
 *
 * <p>
 * Messages deliberately omit Tenant scope, source-event identity and
 * analytical subject keys, so a failure never becomes an incidental disclosure
 * channel.
 * </p>
 */
public final class WorkforceAuthorityChangeFactPersistenceException
        extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public WorkforceAuthorityChangeFactPersistenceException(
            String message) {

        super(message);
    }

    public WorkforceAuthorityChangeFactPersistenceException(
            String message,
            Throwable cause) {

        super(message, cause);
    }
}

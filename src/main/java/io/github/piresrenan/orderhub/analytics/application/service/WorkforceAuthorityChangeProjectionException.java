package io.github.piresrenan.orderhub.analytics.application.service;

/**
 * Analytics-owned failure to project one notified workforce authority-change
 * event.
 *
 * <p>
 * The failure exists so a projection that cannot be completed correctly fails
 * closed instead of inventing analytical state. Letting it escape keeps the
 * durable publication recoverable, which is the only way a missing fact can
 * later be produced.
 * </p>
 *
 * <p>
 * Messages deliberately omit Tenant scope and source-event identity, matching
 * the analytical persistence boundary, so a failure never becomes an incidental
 * disclosure channel.
 * </p>
 */
public final class WorkforceAuthorityChangeProjectionException
        extends RuntimeException {

    private static final long serialVersionUID = 1L;

    public WorkforceAuthorityChangeProjectionException(
            String message) {

        super(message);
    }
}

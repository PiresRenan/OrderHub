package io.github.piresrenan.orderhub.authorization.application.port.out;

/**
 * Stable application-facing failure for authorization persistence operations.
 */
public final class AuthorizationPersistenceException
        extends RuntimeException {

    public AuthorizationPersistenceException(
            String message) {

        super(message);
    }

    public AuthorizationPersistenceException(
            Throwable cause) {

        super(
                "Authorization persistence operation failed",
                cause);
    }
}

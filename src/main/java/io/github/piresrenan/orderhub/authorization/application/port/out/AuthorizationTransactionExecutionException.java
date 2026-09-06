package io.github.piresrenan.orderhub.authorization.application.port.out;

public final class AuthorizationTransactionExecutionException
        extends RuntimeException {

    public AuthorizationTransactionExecutionException(
            Throwable cause) {

        super(
                "Authorization transaction execution failed",
                cause);
    }
}

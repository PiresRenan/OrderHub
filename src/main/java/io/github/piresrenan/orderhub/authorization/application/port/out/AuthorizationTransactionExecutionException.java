package io.github.piresrenan.orderhub.authorization.application.port.out;

public final class AuthorizationTransactionExecutionException
        extends RuntimeException {
    private static final long serialVersionUID = 1L;


    public AuthorizationTransactionExecutionException(
            Throwable cause) {

        super(
                "Authorization transaction execution failed",
                cause);
    }
}

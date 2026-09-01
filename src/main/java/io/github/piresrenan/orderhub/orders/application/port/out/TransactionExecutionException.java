package io.github.piresrenan.orderhub.orders.application.port.out;

/**
 * Represents failure of the transaction infrastructure itself.
 *
 * <p>
 * Business exceptions raised by application work are not wrapped in this
 * exception. Only transaction-management failures are translated through this
 * boundary.
 * </p>
 */
public final class TransactionExecutionException
        extends RuntimeException {

    public TransactionExecutionException(
            Throwable cause) {

        super(
                "Transaction execution failed.",
                cause);
    }
}
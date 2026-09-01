package io.github.piresrenan.orderhub.orders.adapter.out.transaction.spring;

import java.util.Objects;
import java.util.function.Supplier;

import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionOperations;

import io.github.piresrenan.orderhub.orders.application.port.out.TransactionExecutionException;
import io.github.piresrenan.orderhub.orders.application.port.out.TransactionExecutor;

/**
 * Spring transaction adapter for application-owned transaction boundaries.
 */
public final class SpringTransactionExecutor
        implements TransactionExecutor {

    private final TransactionOperations transactionOperations;

    public SpringTransactionExecutor(
            TransactionOperations transactionOperations) {

        this.transactionOperations =
                Objects.requireNonNull(
                        transactionOperations,
                        "transactionOperations");
    }

    @Override
    public <T> T execute(
            Supplier<T> work) {

        Objects.requireNonNull(
                work,
                "work");

        try {
            return transactionOperations.execute(
                    transactionStatus ->
                            work.get());
        } catch (TransactionException exception) {
            throw new TransactionExecutionException(
                    exception);
        }
    }
}
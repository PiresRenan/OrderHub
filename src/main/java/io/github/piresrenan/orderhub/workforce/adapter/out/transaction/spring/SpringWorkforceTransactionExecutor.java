package io.github.piresrenan.orderhub.workforce.adapter.out.transaction.spring;

import java.util.Objects;
import java.util.function.Supplier;

import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionOperations;

import io.github.piresrenan.orderhub.workforce.application.port.out.WorkforceTransactionExecutionException;
import io.github.piresrenan.orderhub.workforce.application.port.out.WorkforceTransactionExecutor;

/**
 * Spring adapter for the workforce-owned transaction boundary.
 */
public final class SpringWorkforceTransactionExecutor
        implements WorkforceTransactionExecutor {

    private final TransactionOperations transactionOperations;

    public SpringWorkforceTransactionExecutor(
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
                    status -> work.get());

        } catch (TransactionException exception) {
            throw new WorkforceTransactionExecutionException(
                    exception);
        }
    }
}

package io.github.piresrenan.orderhub.customers.adapter.out.transaction.spring;

import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionOperations;
import io.github.piresrenan.orderhub.customers.application.port.out.CustomerLinkTransactionExecutor;
import io.github.piresrenan.orderhub.customers.application.port.out.CustomerAccountBindingPersistenceException;

public final class SpringCustomerLinkTransactionExecutor implements CustomerLinkTransactionExecutor {
    private final TransactionOperations transaction;
    /** Requires the supplied owner contracts; construction performs no lifecycle mutation or independent commit. */
    public SpringCustomerLinkTransactionExecutor(TransactionOperations transaction) { this.transaction = Objects.requireNonNull(transaction); }
    /** Joins the configured transaction boundary; technical transaction failures never become successful lifecycle outcomes. */
    @Override public <T> T execute(Supplier<T> work) {
        try { return transaction.execute(status -> work.get()); }
        catch (TransactionException exception) { throw new CustomerAccountBindingPersistenceException(exception); }
    }
}

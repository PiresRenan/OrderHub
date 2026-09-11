package io.github.piresrenan.orderhub.users.adapter.out.persistence.postgresql;

import java.util.function.Supplier;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionOperations;
import io.github.piresrenan.orderhub.users.application.port.out.ExternalIdentityLifecycleTransaction;
import io.github.piresrenan.orderhub.users.application.port.out.ExternalIdentityBindingPersistenceException;

public final class SpringExternalIdentityLifecycleTransaction implements ExternalIdentityLifecycleTransaction {
    private final TransactionOperations transaction;
    /** Requires the supplied owner contracts; construction performs no lifecycle mutation or independent commit. */
    public SpringExternalIdentityLifecycleTransaction(TransactionOperations transaction) { this.transaction = java.util.Objects.requireNonNull(transaction); }
    /** Joins the configured transaction boundary; technical transaction failures never become successful lifecycle outcomes. */
    @Override public <T> T execute(Supplier<T> work) {
        try { return transaction.execute(status -> work.get()); }
        catch (TransactionException exception) { throw new ExternalIdentityBindingPersistenceException(exception); }
    }
}

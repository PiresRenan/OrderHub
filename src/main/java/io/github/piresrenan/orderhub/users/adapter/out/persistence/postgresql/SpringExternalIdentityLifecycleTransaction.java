package io.github.piresrenan.orderhub.users.adapter.out.persistence.postgresql;

import java.util.function.Supplier;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionOperations;
import io.github.piresrenan.orderhub.users.application.port.out.ExternalIdentityLifecycleTransaction;
import io.github.piresrenan.orderhub.users.application.port.out.ExternalIdentityBindingPersistenceException;

public final class SpringExternalIdentityLifecycleTransaction implements ExternalIdentityLifecycleTransaction {
    private final TransactionOperations transaction;
    public SpringExternalIdentityLifecycleTransaction(TransactionOperations transaction) { this.transaction = java.util.Objects.requireNonNull(transaction); }
    @Override public <T> T execute(Supplier<T> work) {
        try { return transaction.execute(status -> work.get()); }
        catch (TransactionException exception) { throw new ExternalIdentityBindingPersistenceException(exception); }
    }
}

package io.github.piresrenan.orderhub.inventory.adapter.out.transaction.spring;

import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.transaction.TransactionException;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.support.TransactionOperations;
import io.github.piresrenan.orderhub.inventory.application.port.in.InventoryAdministrationException;
import io.github.piresrenan.orderhub.inventory.application.port.out.InventoryAdministrationTransactionExecutor;

/** Programmatic REQUIRED transaction; commit failures remain sanitized technical outcomes. */
public final class SpringInventoryAdministrationTransactionExecutor implements InventoryAdministrationTransactionExecutor {
    private final TransactionOperations transactions;
    /** Requires the configured bounded programmatic transaction, preserving REQUIRED participation. */
    public SpringInventoryAdministrationTransactionExecutor(TransactionOperations transactions) {
        this.transactions = Objects.requireNonNull(transactions);
    }
    /** Rolls back the authoritative state and evidence together, including commit-time failures. */
    @Override
    public <T> T execute(Supplier<T> work) {
        try {
            return transactions.execute(status -> work.get());
        } catch (TransactionException | DataAccessException exception) {
            throw new InventoryAdministrationException(InventoryAdministrationException.Reason.TECHNICAL, exception);
        }
    }
}

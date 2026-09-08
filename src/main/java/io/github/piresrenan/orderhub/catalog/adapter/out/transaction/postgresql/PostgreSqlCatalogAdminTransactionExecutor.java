package io.github.piresrenan.orderhub.catalog.adapter.out.transaction.postgresql;

import java.util.Objects;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionOperations;
import io.github.piresrenan.orderhub.catalog.application.port.in.administration.CatalogAdminUnavailableException;
import io.github.piresrenan.orderhub.catalog.application.port.out.CatalogAdminTransactionExecutor;
import io.github.piresrenan.orderhub.catalog.application.port.out.CatalogPersistenceException;

/** Joins one bounded authoritative transaction for Catalog writes and evidence. */
public final class PostgreSqlCatalogAdminTransactionExecutor implements CatalogAdminTransactionExecutor {
    private final TransactionOperations transactions;
    /** Requires infrastructure-owned transaction demarcation. */
    public PostgreSqlCatalogAdminTransactionExecutor(TransactionOperations transactions) {
        this.transactions=Objects.requireNonNull(transactions);
    }
    /** Rolls back all participant writes on failure and exposes only a sanitized technical exception. */
    @Override public <T> T execute(Supplier<T> action) {
        try { return transactions.execute(status -> action.get()); }
        catch (DataAccessException | TransactionException | CatalogPersistenceException exception) {
            throw new CatalogAdminUnavailableException();
        }
    }
}

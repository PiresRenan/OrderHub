package io.github.piresrenan.orderhub.catalog.application.port.out;
import java.util.function.Supplier;
/** One bounded authoritative transaction for mutation and evidence. */
public interface CatalogAdminTransactionExecutor {
    /** Commits all participants together or rolls their effects back on failure. */
    <T> T execute(Supplier<T> action);
}

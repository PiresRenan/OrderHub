package io.github.piresrenan.orderhub.orders.application.port.out;

import java.util.function.Supplier;

/**
 * Executes application work inside one transaction boundary.
 *
 * <p>
 * The application defines transaction ownership without depending on Spring,
 * JDBC or a concrete transaction manager. Infrastructure adapters implement
 * the actual transaction mechanism.
 * </p>
 */
public interface TransactionExecutor {

    /**
     * Executes the supplied work inside one transaction.
     *
     * @param work application work whose durable effects belong to one
     *             transaction
     * @param <T> result type produced by the work
     * @return result produced by the successfully completed work
     * @throws TransactionExecutionException when transaction infrastructure
     *         cannot establish or complete the transaction
     */
    <T> T execute(
            Supplier<T> work);
}
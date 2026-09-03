package io.github.piresrenan.orderhub.workforce.application.port.out;

import java.util.function.Supplier;

/**
 * Executes workforce application work inside one transaction boundary.
 *
 * <p>
 * The workforce application owns the transaction contract without depending
 * on Spring, JDBC or another business module.
 * </p>
 */
public interface WorkforceTransactionExecutor {

    <T> T execute(
            Supplier<T> work);
}

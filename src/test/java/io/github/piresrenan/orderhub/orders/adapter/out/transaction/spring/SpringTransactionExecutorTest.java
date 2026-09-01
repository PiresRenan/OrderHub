package io.github.piresrenan.orderhub.orders.adapter.out.transaction.spring;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.Test;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.SimpleTransactionStatus;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;

import io.github.piresrenan.orderhub.orders.application.port.out.TransactionExecutionException;

class SpringTransactionExecutorTest {

    @Test
    void executesWorkThroughTransactionOperationsAndReturnsResult() {

        var executions =
                new AtomicInteger();

        TransactionOperations operations =
                new TransactionOperations() {

                    @Override
                    public <T> T execute(
                            TransactionCallback<T> action)
                            throws TransactionException {

                        executions.incrementAndGet();

                        return action.doInTransaction(
                                new SimpleTransactionStatus());
                    }
                };

        var executor =
                new SpringTransactionExecutor(
                        operations);

        var result =
                executor.execute(
                        () -> "committed");

        assertThat(result)
                .isEqualTo("committed");

        assertThat(executions)
                .hasValue(1);
    }

    @Test
    void propagatesBusinessRuntimeFailureWithoutInfrastructureWrapping() {

        TransactionOperations operations =
                new TransactionOperations() {

                    @Override
                    public <T> T execute(
                            TransactionCallback<T> action)
                            throws TransactionException {

                        return action.doInTransaction(
                                new SimpleTransactionStatus());
                    }
                };

        var executor =
                new SpringTransactionExecutor(
                        operations);

        var businessFailure =
                new IllegalStateException(
                        "synthetic-business-failure");

        assertThatThrownBy(() ->
                executor.execute(
                        () -> {
                            throw businessFailure;
                        }))
                .isSameAs(
                        businessFailure);
    }

    @Test
    void translatesTransactionInfrastructureFailure() {

        var infrastructureFailure =
                new CannotCreateTransactionException(
                        "synthetic-transaction-failure");

        TransactionOperations operations =
                new TransactionOperations() {

                    @Override
                    public <T> T execute(
                            TransactionCallback<T> action)
                            throws TransactionException {

                        throw infrastructureFailure;
                    }
                };

        var executor =
                new SpringTransactionExecutor(
                        operations);

        assertThatThrownBy(() ->
                executor.execute(
                        () -> "unreachable"))
                .isInstanceOf(
                        TransactionExecutionException.class)
                .hasMessage(
                        "Transaction execution failed.")
                .hasCause(
                        infrastructureFailure);
    }
}
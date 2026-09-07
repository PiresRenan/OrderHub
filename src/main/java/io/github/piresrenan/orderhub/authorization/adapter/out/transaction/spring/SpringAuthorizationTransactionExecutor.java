package io.github.piresrenan.orderhub.authorization.adapter.out.transaction.spring;

import java.util.Objects;
import java.util.function.Supplier;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.piresrenan.orderhub.authorization.application.port.out.AuthorizationTransactionExecutionException;
import io.github.piresrenan.orderhub.authorization.application.port.out.AuthorizationTransactionExecutor;

public final class SpringAuthorizationTransactionExecutor
        implements AuthorizationTransactionExecutor {

    private final TransactionTemplate transactionTemplate;

    public SpringAuthorizationTransactionExecutor(
            PlatformTransactionManager transactionManager) {

        this.transactionTemplate =
                new TransactionTemplate(
                        Objects.requireNonNull(
                                transactionManager,
                                "transactionManager"));
    }

    @Override
    public <T> T execute(
            Supplier<T> work) {

        Objects.requireNonNull(
                work,
                "work");

        try {
            return transactionTemplate.execute(
                    status ->
                            work.get());

        } catch (TransactionException exception) {

            throw new AuthorizationTransactionExecutionException(
                    exception);
        }
    }
}

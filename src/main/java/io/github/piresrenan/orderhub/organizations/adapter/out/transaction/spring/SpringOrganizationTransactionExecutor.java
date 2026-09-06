package io.github.piresrenan.orderhub.organizations.adapter.out.transaction.spring;

import java.util.Objects;
import java.util.function.Supplier;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.piresrenan.orderhub.organizations.application.port.out.OrganizationTransactionExecutor;

public final class SpringOrganizationTransactionExecutor
        implements OrganizationTransactionExecutor {

    private final TransactionTemplate transactions;

    public SpringOrganizationTransactionExecutor(PlatformTransactionManager transactionManager) {
        this.transactions = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
    }

    @Override
    public <T> T execute(Supplier<T> work) {
        return transactions.execute(status -> Objects.requireNonNull(work, "work").get());
    }
}

package io.github.piresrenan.orderhub.tenants.adapter.out.transaction.spring;

import java.util.Objects;
import java.util.function.Supplier;

import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import io.github.piresrenan.orderhub.tenants.application.port.out.TenantTransactionExecutor;

public final class SpringTenantTransactionExecutor implements TenantTransactionExecutor {

    private final TransactionTemplate transactions;

    public SpringTenantTransactionExecutor(PlatformTransactionManager transactionManager) {
        this.transactions = new TransactionTemplate(
                Objects.requireNonNull(transactionManager, "transactionManager"));
    }

    @Override
    public <T> T execute(Supplier<T> work) {
        return transactions.execute(status ->
                Objects.requireNonNull(work, "work").get());
    }
}

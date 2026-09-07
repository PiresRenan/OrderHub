package io.github.piresrenan.orderhub.tenants.application.port.out;

import java.util.function.Supplier;

@FunctionalInterface
public interface TenantTransactionExecutor {
    <T> T execute(Supplier<T> work);
}

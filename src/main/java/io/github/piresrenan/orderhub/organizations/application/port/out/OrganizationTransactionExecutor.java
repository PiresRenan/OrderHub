package io.github.piresrenan.orderhub.organizations.application.port.out;

import java.util.function.Supplier;

@FunctionalInterface
public interface OrganizationTransactionExecutor {
    <T> T execute(Supplier<T> work);
}

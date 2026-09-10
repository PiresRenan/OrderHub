package io.github.piresrenan.orderhub.customers.application.port.out;

import java.util.function.Supplier;

public interface CustomerLinkTransactionExecutor {
    <T> T execute(Supplier<T> work);
}

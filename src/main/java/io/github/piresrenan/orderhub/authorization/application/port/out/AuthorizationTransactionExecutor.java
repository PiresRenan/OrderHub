package io.github.piresrenan.orderhub.authorization.application.port.out;

import java.util.function.Supplier;

@FunctionalInterface
public interface AuthorizationTransactionExecutor {

    <T> T execute(
            Supplier<T> work);
}

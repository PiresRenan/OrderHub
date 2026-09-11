package io.github.piresrenan.orderhub.customers.application.port.out;

import java.util.function.Supplier;

public interface CustomerLinkTransactionExecutor {
    /** Joins the configured transaction boundary; technical transaction failures never become successful lifecycle outcomes. */
    <T> T execute(Supplier<T> work);
}

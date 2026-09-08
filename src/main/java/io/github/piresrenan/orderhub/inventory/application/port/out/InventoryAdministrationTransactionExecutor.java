package io.github.piresrenan.orderhub.inventory.application.port.out;

import java.util.function.Supplier;

/** Owns one bounded physical transaction for stock and its authoritative evidence. */
public interface InventoryAdministrationTransactionExecutor {
    <T> T execute(Supplier<T> work);
}

package io.github.piresrenan.orderhub.inventory.application.port.in;

/**
 * Application boundary that durably commits inventory demand for one Order.
 *
 * <p>
 * Transaction ownership remains with the calling use case. Inventory
 * repositories therefore participate in the caller-owned transaction.
 * </p>
 */
public interface CommitOrderInventoryUseCase {

    void commit(
            CommitOrderInventoryCommand command);
}
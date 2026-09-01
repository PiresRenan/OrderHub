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

    /**
     * Commits every demand line belonging to one Order.
     *
     * @param command tenant-scoped Order demand
     * @return aggregate allocation outcome after every durable Inventory effect
     */
    InventoryAllocationOutcome commit(
            CommitOrderInventoryCommand command);
}
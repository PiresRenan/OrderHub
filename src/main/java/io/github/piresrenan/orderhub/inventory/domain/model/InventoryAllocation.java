package io.github.piresrenan.orderhub.inventory.domain.model;

/**
 * Immutable result of applying one demand request to an InventoryPosition.
 *
 * <p>
 * Every allocation reconciles exactly:
 * </p>
 *
 * <pre>
 * requestedQuantity = allocatedQuantity + backorderedQuantity
 * </pre>
 *
 * @param position             resulting inventory state
 * @param requestedQuantity    complete accepted demand
 * @param allocatedQuantity    demand physically covered by inventory
 * @param backorderedQuantity  accepted demand not physically covered
 */
public record InventoryAllocation(
        InventoryPosition position,
        long requestedQuantity,
        long allocatedQuantity,
        long backorderedQuantity) {

    /**
     * Validates the complete allocation result before it can cross the Inventory
     * domain boundary.
     */
    public InventoryAllocation {

        if (position == null) {
            throw new IllegalArgumentException(
                    "Inventory allocation resulting position is required");
        }

        if (requestedQuantity <= 0) {
            throw new IllegalArgumentException(
                    "Inventory allocation requested quantity must be greater than zero");
        }

        if (allocatedQuantity < 0) {
            throw new IllegalArgumentException(
                    "Inventory allocation allocated quantity must not be negative");
        }

        if (backorderedQuantity < 0) {
            throw new IllegalArgumentException(
                    "Inventory allocation backordered quantity must not be negative");
        }

        /*
         * Validate reconciliation without evaluating
         *
         *     allocatedQuantity + backorderedQuantity
         *
         * because that addition could itself overflow.
         */
        if (allocatedQuantity > requestedQuantity
                || backorderedQuantity > requestedQuantity
                || allocatedQuantity
                        != requestedQuantity - backorderedQuantity) {

            throw new IllegalArgumentException(
                    "Inventory allocation quantities must satisfy requested = allocated + backordered");
        }
    }
}
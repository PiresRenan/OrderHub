package io.github.piresrenan.orderhub.inventory.application.port.in;

import java.util.List;
import java.util.UUID;

/**
 * Tenant-scoped inventory demand accepted from another application module.
 *
 * <p>
 * Individual demands intentionally preserve the caller's original lines.
 * Inventory owns aggregation and deterministic processing order.
 * </p>
 */
public record CommitOrderInventoryCommand(
        UUID tenantId,
        UUID orderId,
        List<Demand> demands) {

    public CommitOrderInventoryCommand {

        if (tenantId == null) {
            throw new IllegalArgumentException(
                    "Inventory commitment tenant id is required");
        }

        if (orderId == null) {
            throw new IllegalArgumentException(
                    "Inventory commitment order id is required");
        }

        if (demands == null || demands.isEmpty()) {
            throw new IllegalArgumentException(
                    "Inventory commitment must contain at least one demand");
        }

        if (demands.stream().anyMatch(demand -> demand == null)) {
            throw new IllegalArgumentException(
                    "Inventory commitment demands must not contain null elements");
        }

        demands =
                List.copyOf(
                        demands);
    }

    public record Demand(
            UUID variantId,
            long quantity) {

        public Demand {

            if (variantId == null) {
                throw new IllegalArgumentException(
                        "Inventory demand variant id is required");
            }

            if (quantity <= 0) {
                throw new IllegalArgumentException(
                        "Inventory demand quantity must be greater than zero");
            }
        }
    }
}
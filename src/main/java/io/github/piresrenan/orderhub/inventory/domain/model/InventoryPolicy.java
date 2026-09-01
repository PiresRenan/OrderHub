package io.github.piresrenan.orderhub.inventory.domain.model;

/**
 * Defines how a Tenant accepts demand when immediately allocable inventory
 * cannot satisfy the complete requested quantity.
 */
public enum InventoryPolicy {

    /**
     * Demand must be completely covered by available-to-promise inventory.
     */
    DENY,

    /**
     * Demand may be accepted while the uncovered quantity becomes backordered.
     */
    ALLOW_BACKORDER
}
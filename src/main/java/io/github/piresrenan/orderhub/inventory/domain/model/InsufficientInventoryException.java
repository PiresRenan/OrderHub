package io.github.piresrenan.orderhub.inventory.domain.model;

/**
 * Indicates that inventory governed by the DENY policy cannot completely
 * satisfy requested demand.
 *
 * <p>
 * The exception intentionally exposes no Tenant, Variant or inventory
 * quantities.
 * </p>
 */
public final class InsufficientInventoryException
        extends RuntimeException {

    public InsufficientInventoryException() {
        super(
                "Insufficient inventory to commit requested quantity.");
    }
}
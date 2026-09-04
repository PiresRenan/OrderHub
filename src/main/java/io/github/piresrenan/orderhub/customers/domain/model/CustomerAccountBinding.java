package io.github.piresrenan.orderhub.customers.domain.model;

import java.util.UUID;

/**
 * Explicit account relationship between one Customer and one internal User
 * inside exactly one Tenant.
 *
 * <p>
 * This relationship establishes no global one-to-one cardinality and carries
 * no Staff role or authentication semantics.
 * </p>
 */
public record CustomerAccountBinding(
        UUID tenantId,
        UUID customerId,
        UUID userId) {

    public CustomerAccountBinding {

        if (tenantId == null) {
            throw new IllegalArgumentException(
                    "Tenant ID is required");
        }

        if (customerId == null) {
            throw new IllegalArgumentException(
                    "Customer ID is required");
        }

        if (userId == null) {
            throw new IllegalArgumentException(
                    "User ID is required");
        }
    }
}

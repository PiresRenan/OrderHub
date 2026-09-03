package io.github.piresrenan.orderhub.customers.domain.model;

import java.util.UUID;

/**
 * Commercial Customer identity scoped to exactly one Tenant.
 *
 * <p>
 * Authentication identity and account ownership relationships are deliberately
 * modeled separately.
 * </p>
 */
public record CustomerProfile(
        UUID tenantId,
        UUID customerId) {

    public CustomerProfile {

        if (tenantId == null) {
            throw new IllegalArgumentException(
                    "Tenant ID is required");
        }

        if (customerId == null) {
            throw new IllegalArgumentException(
                    "Customer ID is required");
        }
    }
}

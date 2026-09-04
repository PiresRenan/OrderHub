package io.github.piresrenan.orderhub.customers.application.port.out;

import java.util.UUID;

/**
 * Persistence boundary for exact Customer account relationships.
 */
public interface CustomerAccountBindingRepository {

    boolean existsExact(
            UUID tenantId,
            UUID customerId,
            UUID userId);
}

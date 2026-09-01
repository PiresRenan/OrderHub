package io.github.piresrenan.orderhub.catalog.application.port.out;

import java.util.Optional;
import java.util.UUID;

import io.github.piresrenan.orderhub.catalog.domain.model.Product;

/**
 * Persistence boundary for tenant-owned Products.
 */
public interface ProductRepository {

    /**
     * Persists the complete Product, including its Category assignments.
     */
    Product save(Product product);

    /**
     * Finds one Product only inside the supplied Tenant boundary.
     */
    Optional<Product> findById(
            UUID tenantId,
            UUID productId);
}
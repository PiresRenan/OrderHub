package io.github.piresrenan.orderhub.catalog.application.port.out;

import java.util.Optional;
import java.util.UUID;

import io.github.piresrenan.orderhub.catalog.domain.model.Category;

/**
 * Persistence boundary for tenant-owned Category hierarchy nodes.
 */
public interface CategoryRepository {

    Category save(
            Category category);

    Optional<Category> findById(
            UUID tenantId,
            UUID categoryId);
}
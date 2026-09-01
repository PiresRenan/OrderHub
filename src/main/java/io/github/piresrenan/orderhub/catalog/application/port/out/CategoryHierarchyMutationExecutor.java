package io.github.piresrenan.orderhub.catalog.application.port.out;

import java.util.UUID;
import java.util.function.Supplier;

import io.github.piresrenan.orderhub.catalog.domain.model.Category;

/**
 * Executes one tenant-scoped Category hierarchy mutation atomically.
 */
@FunctionalInterface
public interface CategoryHierarchyMutationExecutor {

    Category execute(
            UUID tenantId,
            Supplier<Category> action);
}

package io.github.piresrenan.orderhub.catalog.application.port.in;

import io.github.piresrenan.orderhub.catalog.domain.model.Category;

/**
 * Application boundary for creating or changing a tenant-owned Category.
 *
 * <p>
 * Hierarchy integrity is validated before persistence so callers cannot
 * create missing-parent, cross-tenant or cyclic ancestry through this
 * boundary.
 * </p>
 */
public interface SaveCategoryUseCase {

    Category save(
            Category category);
}
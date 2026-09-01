package io.github.piresrenan.orderhub.catalog.application.service;

import java.util.HashSet;
import java.util.Objects;
import java.util.UUID;

import io.github.piresrenan.orderhub.catalog.application.port.in.CategoryHierarchyViolationException;
import io.github.piresrenan.orderhub.catalog.application.port.in.SaveCategoryUseCase;
import io.github.piresrenan.orderhub.catalog.application.port.out.CategoryRepository;
import io.github.piresrenan.orderhub.catalog.domain.model.Category;

/**
 * Validates Category ancestry before persisting a hierarchy mutation.
 */
public final class SaveCategoryService
        implements SaveCategoryUseCase {

    private final CategoryRepository categoryRepository;

    public SaveCategoryService(
            CategoryRepository categoryRepository) {

        this.categoryRepository =
                Objects.requireNonNull(
                        categoryRepository,
                        "categoryRepository");
    }

    @Override
    public Category save(
            Category category) {

        Objects.requireNonNull(
                category,
                "category");

        validateHierarchy(category);

        return categoryRepository.save(
                category);
    }

    private void validateHierarchy(
            Category candidate) {

        var parentCategoryId =
                candidate.parentCategoryId();

        if (parentCategoryId == null) {
            return;
        }

        /*
         * The candidate itself starts in the visited set. If traversal from
         * its proposed parent reaches the candidate, the mutation would
         * create a cycle.
         *
         * The same set also detects an ancestry chain that was already
         * cyclic before this operation, preventing an infinite traversal and
         * failing closed instead.
         */
        var visitedCategoryIds =
                new HashSet<UUID>();

        visitedCategoryIds.add(
                candidate.id());

        var currentCategoryId =
                parentCategoryId;

        while (currentCategoryId != null) {

            if (!visitedCategoryIds.add(
                    currentCategoryId)) {

                throw new CategoryHierarchyViolationException();
            }

            var ancestor =
                    categoryRepository
                            .findById(
                                    candidate.tenantId(),
                                    currentCategoryId)
                            .orElseThrow(
                                    CategoryHierarchyViolationException::new);

            /*
             * CategoryRepository is tenant-scoped by contract. Verify the
             * returned identity anyway so a broken adapter cannot silently
             * weaken the application boundary.
             */
            if (!ancestor.tenantId().equals(
                    candidate.tenantId())
                    || !ancestor.id().equals(
                            currentCategoryId)) {

                throw new CategoryHierarchyViolationException();
            }

            currentCategoryId =
                    ancestor.parentCategoryId();
        }
    }
}
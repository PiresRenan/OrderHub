package io.github.piresrenan.orderhub.catalog.application.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.HashMap;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import io.github.piresrenan.orderhub.catalog.application.port.in.CategoryHierarchyViolationException;
import io.github.piresrenan.orderhub.catalog.application.port.in.SaveCategoryUseCase;
import io.github.piresrenan.orderhub.catalog.application.port.out.CategoryRepository;
import io.github.piresrenan.orderhub.catalog.domain.model.Category;

/**
 * Application-boundary acceptance tests for tenant-scoped Category hierarchy
 * integrity.
 */
class SaveCategoryServiceTest {

    private static final UUID TENANT_A =
            UUID.fromString(
                    "11111111-1111-1111-1111-111111111111");

    private static final UUID TENANT_B =
            UUID.fromString(
                    "22222222-2222-2222-2222-222222222222");

    private InMemoryCategoryRepository repository;
    private SaveCategoryUseCase service;

    @BeforeEach
    void setUp() {

        repository =
                new InMemoryCategoryRepository();

        service =
                new SaveCategoryService(
                        repository,
                        (tenantId, action) ->
                                action.get());
    }

    @Test
    void savesRootCategoryWithoutHierarchyLookup() {

        var rootId =
                UUID.randomUUID();

        var root =
                category(
                        rootId,
                        TENANT_A,
                        null,
                        "root");

        var saved =
                service.save(root);

        assertThat(saved)
                .isSameAs(root);

        assertThat(
                repository.findById(
                        TENANT_A,
                        rootId))
                .contains(root);

        assertThat(repository.saveCalls())
                .isEqualTo(1);
    }

    @Test
    void savesArbitraryDepthAcyclicHierarchy() {

        var root =
                category(
                        UUID.randomUUID(),
                        TENANT_A,
                        null,
                        "root");

        var levelOne =
                category(
                        UUID.randomUUID(),
                        TENANT_A,
                        root.id(),
                        "level-one");

        var levelTwo =
                category(
                        UUID.randomUUID(),
                        TENANT_A,
                        levelOne.id(),
                        "level-two");

        var levelThree =
                category(
                        UUID.randomUUID(),
                        TENANT_A,
                        levelTwo.id(),
                        "level-three");

        service.save(root);
        service.save(levelOne);
        service.save(levelTwo);
        service.save(levelThree);

        assertThat(
                repository.findById(
                        TENANT_A,
                        levelThree.id()))
                .contains(levelThree);

        assertThat(repository.saveCalls())
                .isEqualTo(4);
    }

    @Test
    void rejectsMissingParentBeforePersistence() {

        var candidate =
                category(
                        UUID.randomUUID(),
                        TENANT_A,
                        UUID.randomUUID(),
                        "orphan");

        assertThatThrownBy(() ->
                service.save(candidate))
                .isInstanceOf(
                        CategoryHierarchyViolationException.class)
                .hasMessage(
                        "Category hierarchy is invalid.");

        assertThat(
                repository.findById(
                        TENANT_A,
                        candidate.id()))
                .isEmpty();

        assertThat(repository.saveCalls())
                .isZero();
    }

    @Test
    void rejectsParentThatExistsOnlyInAnotherTenant() {

        var sharedParentId =
                UUID.randomUUID();

        repository.seed(
                category(
                        sharedParentId,
                        TENANT_B,
                        null,
                        "foreign-parent"));

        var candidate =
                category(
                        UUID.randomUUID(),
                        TENANT_A,
                        sharedParentId,
                        "tenant-a-child");

        assertThatThrownBy(() ->
                service.save(candidate))
                .isInstanceOf(
                        CategoryHierarchyViolationException.class)
                .hasMessage(
                        "Category hierarchy is invalid.");

        assertThat(
                repository.findById(
                        TENANT_A,
                        candidate.id()))
                .isEmpty();

        assertThat(repository.saveCalls())
                .isZero();
    }

    @Test
    void rejectsTwoNodeCycleWhenRootIsReparentedUnderItsChild() {

        var rootId =
                UUID.randomUUID();

        var childId =
                UUID.randomUUID();

        var root =
                category(
                        rootId,
                        TENANT_A,
                        null,
                        "root");

        var child =
                category(
                        childId,
                        TENANT_A,
                        rootId,
                        "child");

        service.save(root);
        service.save(child);

        var savesBeforeInvalidReparent =
                repository.saveCalls();

        var invalidRoot =
                category(
                        rootId,
                        TENANT_A,
                        childId,
                        "root");

        assertThatThrownBy(() ->
                service.save(invalidRoot))
                .isInstanceOf(
                        CategoryHierarchyViolationException.class)
                .hasMessage(
                        "Category hierarchy is invalid.");

        var persistedRoot =
                repository.findById(
                        TENANT_A,
                        rootId)
                        .orElseThrow();

        assertThat(persistedRoot.parentCategoryId())
                .isNull();

        assertThat(repository.saveCalls())
                .isEqualTo(savesBeforeInvalidReparent);
    }

    @Test
    void rejectsCycleAcrossArbitraryHierarchyDepth() {

        var rootId =
                UUID.randomUUID();

        var levelOneId =
                UUID.randomUUID();

        var levelTwoId =
                UUID.randomUUID();

        var levelThreeId =
                UUID.randomUUID();

        service.save(
                category(
                        rootId,
                        TENANT_A,
                        null,
                        "root"));

        service.save(
                category(
                        levelOneId,
                        TENANT_A,
                        rootId,
                        "level-one"));

        service.save(
                category(
                        levelTwoId,
                        TENANT_A,
                        levelOneId,
                        "level-two"));

        service.save(
                category(
                        levelThreeId,
                        TENANT_A,
                        levelTwoId,
                        "level-three"));

        var invalidRoot =
                category(
                        rootId,
                        TENANT_A,
                        levelThreeId,
                        "root");

        assertThatThrownBy(() ->
                service.save(invalidRoot))
                .isInstanceOf(
                        CategoryHierarchyViolationException.class)
                .hasMessage(
                        "Category hierarchy is invalid.");

        assertThat(
                repository.findById(
                        TENANT_A,
                        rootId)
                        .orElseThrow()
                        .parentCategoryId())
                .isNull();
    }

    @Test
    void failsClosedWhenStoredAncestorChainIsAlreadyCyclic() {

        var ancestorAId =
                UUID.randomUUID();

        var ancestorBId =
                UUID.randomUUID();

        repository.seed(
                category(
                        ancestorAId,
                        TENANT_A,
                        ancestorBId,
                        "ancestor-a"));

        repository.seed(
                category(
                        ancestorBId,
                        TENANT_A,
                        ancestorAId,
                        "ancestor-b"));

        var candidate =
                category(
                        UUID.randomUUID(),
                        TENANT_A,
                        ancestorAId,
                        "candidate");

        assertThatThrownBy(() ->
                service.save(candidate))
                .isInstanceOf(
                        CategoryHierarchyViolationException.class)
                .hasMessage(
                        "Category hierarchy is invalid.");

        assertThat(
                repository.findById(
                        TENANT_A,
                        candidate.id()))
                .isEmpty();

        assertThat(repository.saveCalls())
                .isZero();
    }

    @Test
    void allowsSafeReparentingToAnotherAcyclicBranch() {

        var rootAId =
                UUID.randomUUID();

        var rootBId =
                UUID.randomUUID();

        var childId =
                UUID.randomUUID();

        service.save(
                category(
                        rootAId,
                        TENANT_A,
                        null,
                        "root-a"));

        service.save(
                category(
                        rootBId,
                        TENANT_A,
                        null,
                        "root-b"));

        service.save(
                category(
                        childId,
                        TENANT_A,
                        rootAId,
                        "child"));

        var reparented =
                category(
                        childId,
                        TENANT_A,
                        rootBId,
                        "child");

        service.save(reparented);

        var persisted =
                repository.findById(
                        TENANT_A,
                        childId)
                        .orElseThrow();

        assertThat(persisted.parentCategoryId())
                .isEqualTo(rootBId);
    }

    private static Category category(
            UUID id,
            UUID tenantId,
            UUID parentCategoryId,
            String slug) {

        return Category.create(
                id,
                tenantId,
                parentCategoryId,
                "Category " + slug,
                slug,
                null);
    }

    private static final class InMemoryCategoryRepository
            implements CategoryRepository {

        private final Map<CategoryKey, Category> categories =
                new HashMap<>();

        private int saveCalls;

        @Override
        public Category save(
                Category category) {

            categories.put(
                    new CategoryKey(
                            category.tenantId(),
                            category.id()),
                    category);

            saveCalls++;

            return category;
        }

        @Override
        public Optional<Category> findById(
                UUID tenantId,
                UUID categoryId) {

            return Optional.ofNullable(
                    categories.get(
                            new CategoryKey(
                                    tenantId,
                                    categoryId)));
        }

        void seed(
                Category category) {

            categories.put(
                    new CategoryKey(
                            category.tenantId(),
                            category.id()),
                    category);
        }

        int saveCalls() {
            return saveCalls;
        }
    }

    private record CategoryKey(
            UUID tenantId,
            UUID categoryId) {
    }
}

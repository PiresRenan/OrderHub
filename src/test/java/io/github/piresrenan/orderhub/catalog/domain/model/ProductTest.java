package io.github.piresrenan.orderhub.catalog.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class ProductTest {

    @Test
    void createsDraftProductWithCommercialIdentityAndCategories() {
        var id = UUID.randomUUID();
        var tenantId = UUID.randomUUID();
        var categoryA = UUID.randomUUID();
        var categoryB = UUID.randomUUID();

        var product = Product.create(
                id,
                tenantId,
                "\u00A0 Professional Monitor \u00A0",
                "professional-monitor",
                "27-inch professional display",
                List.of(categoryA, categoryB));

        assertThat(product.id())
                .isEqualTo(id);

        assertThat(product.tenantId())
                .isEqualTo(tenantId);

        assertThat(product.name())
                .isEqualTo("Professional Monitor");

        assertThat(product.slug())
                .isEqualTo("professional-monitor");

        assertThat(product.description())
                .isEqualTo("27-inch professional display");

        assertThat(product.status())
                .isEqualTo(ProductStatus.DRAFT);

        assertThat(product.categoryIds())
                .containsExactlyInAnyOrder(
                        categoryA,
                        categoryB);
    }

    @Test
    void allowsDraftProductWithoutCategoryAssignment() {
        var product = Product.create(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Uncategorized Product",
                "uncategorized-product",
                null,
                List.of());

        assertThat(product.categoryIds())
                .isEmpty();

        assertThat(product.status())
                .isEqualTo(ProductStatus.DRAFT);
    }

    @Test
    void preservesMeaningfulUnicodeAndInternalNameContent() {
        var product = Product.create(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Câmera Profissional — Edição 2026",
                "camera-profissional-2026",
                null,
                List.of());

        assertThat(product.name())
                .isEqualTo("Câmera Profissional — Edição 2026");
    }

    @Test
    void acceptsMissingOptionalDescription() {
        var product = Product.create(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Professional Monitor",
                "professional-monitor",
                null,
                List.of());

        assertThat(product.description())
                .isNull();
    }

    @Test
    void preservesDescriptionExactly() {
        var description =
                "Color-accurate display.\nFactory calibrated.";

        var product = Product.create(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Professional Monitor",
                "professional-monitor",
                description,
                List.of());

        assertThat(product.description())
                .isEqualTo(description);
    }

    @Test
    void rejectsMissingProductId() {
        assertThatThrownBy(() -> Product.create(
                null,
                UUID.randomUUID(),
                "Professional Monitor",
                "professional-monitor",
                null,
                List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Product id is required");
    }

    @Test
    void rejectsMissingTenantId() {
        assertThatThrownBy(() -> Product.create(
                UUID.randomUUID(),
                null,
                "Professional Monitor",
                "professional-monitor",
                null,
                List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Product tenant id is required");
    }

    @Test
    void rejectsMissingName() {
        assertThatThrownBy(() -> Product.create(
                UUID.randomUUID(),
                UUID.randomUUID(),
                null,
                "professional-monitor",
                null,
                List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Product name is required");
    }

    @Test
    void rejectsUnicodeBlankName() {
        assertThatThrownBy(() -> Product.create(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "\u00A0\u2007",
                "professional-monitor",
                null,
                List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Product name must not be blank");
    }

    @Test
    void rejectsInvalidProductSlug() {
        assertThatThrownBy(() -> Product.create(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Professional Monitor",
                "professional monitor",
                null,
                List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Catalog slug contains unsupported characters");
    }

    @Test
    void rejectsMissingCategoryCollection() {
        assertThatThrownBy(() -> Product.create(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Professional Monitor",
                "professional-monitor",
                null,
                null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Product category ids are required");
    }

    @Test
    void rejectsNullCategoryIdentity() {
        var categoryIds =
                new ArrayList<UUID>();

        categoryIds.add(UUID.randomUUID());
        categoryIds.add(null);

        assertThatThrownBy(() -> Product.create(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Professional Monitor",
                "professional-monitor",
                null,
                categoryIds))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Product category ids must not contain null values");
    }

    @Test
    void rejectsDuplicateCategoryAssignment() {
        var categoryId = UUID.randomUUID();

        assertThatThrownBy(() -> Product.create(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Professional Monitor",
                "professional-monitor",
                null,
                List.of(
                        categoryId,
                        categoryId)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Product category ids must not contain duplicates");
    }

    @Test
    void protectsCategoryAssignmentsFromExternalMutation() {
        var originalCategory = UUID.randomUUID();
        var injectedCategory = UUID.randomUUID();

        var categoryIds =
                new ArrayList<UUID>();

        categoryIds.add(originalCategory);

        var product = Product.create(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Professional Monitor",
                "professional-monitor",
                null,
                categoryIds);

        categoryIds.add(injectedCategory);

        assertThat(product.categoryIds())
                .containsExactly(originalCategory);
    }
}
package io.github.piresrenan.orderhub.catalog.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class CategoryTest {

    @Test
    void createsRootCategoryWithCanonicalDisplayName() {
        var id = UUID.randomUUID();
        var tenantId = UUID.randomUUID();

        var category = Category.create(
                id,
                tenantId,
                null,
                "\u00A0 Electronics \u00A0",
                "electronics",
                "Products and accessories");

        assertThat(category.id())
                .isEqualTo(id);

        assertThat(category.tenantId())
                .isEqualTo(tenantId);

        assertThat(category.parentCategoryId())
                .isNull();

        assertThat(category.name())
                .isEqualTo("Electronics");

        assertThat(category.slug())
                .isEqualTo("electronics");

        assertThat(category.description())
                .isEqualTo("Products and accessories");
    }

    @Test
    void createsChildCategoryWithExplicitParent() {
        var parentId = UUID.randomUUID();

        var category = Category.create(
                UUID.randomUUID(),
                UUID.randomUUID(),
                parentId,
                "Smartphones",
                "smartphones",
                null);

        assertThat(category.parentCategoryId())
                .isEqualTo(parentId);
    }

    @Test
    void preservesMeaningfulUnicodeAndInternalNameContent() {
        var category = Category.create(
                UUID.randomUUID(),
                UUID.randomUUID(),
                null,
                "Áudio & Vídeo Profissional",
                "audio-video",
                null);

        assertThat(category.name())
                .isEqualTo("Áudio & Vídeo Profissional");
    }

    @Test
    void acceptsMissingOptionalDescription() {
        var category = Category.create(
                UUID.randomUUID(),
                UUID.randomUUID(),
                null,
                "Electronics",
                "electronics",
                null);

        assertThat(category.description())
                .isNull();
    }

    @Test
    void preservesDescriptionExactly() {
        var description =
                "First line.\nSecond line.";

        var category = Category.create(
                UUID.randomUUID(),
                UUID.randomUUID(),
                null,
                "Electronics",
                "electronics",
                description);

        assertThat(category.description())
                .isEqualTo(description);
    }

    @Test
    void rejectsMissingCategoryId() {
        assertThatThrownBy(() -> Category.create(
                null,
                UUID.randomUUID(),
                null,
                "Electronics",
                "electronics",
                null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Category id is required");
    }

    @Test
    void rejectsMissingTenantId() {
        assertThatThrownBy(() -> Category.create(
                UUID.randomUUID(),
                null,
                null,
                "Electronics",
                "electronics",
                null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Category tenant id is required");
    }

    @Test
    void rejectsMissingName() {
        assertThatThrownBy(() -> Category.create(
                UUID.randomUUID(),
                UUID.randomUUID(),
                null,
                null,
                "electronics",
                null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Category name is required");
    }

    @Test
    void rejectsUnicodeBlankName() {
        assertThatThrownBy(() -> Category.create(
                UUID.randomUUID(),
                UUID.randomUUID(),
                null,
                "\u00A0\u2007",
                "electronics",
                null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Category name must not be blank");
    }

    @Test
    void rejectsSelfParentingCategory() {
        var id = UUID.randomUUID();

        assertThatThrownBy(() -> Category.create(
                id,
                UUID.randomUUID(),
                id,
                "Electronics",
                "electronics",
                null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Category must not be its own parent");
    }

    @Test
    void rejectsInvalidCategorySlug() {
        assertThatThrownBy(() -> Category.create(
                UUID.randomUUID(),
                UUID.randomUUID(),
                null,
                "Electronics",
                "electronics store",
                null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Catalog slug contains unsupported characters");
    }
}
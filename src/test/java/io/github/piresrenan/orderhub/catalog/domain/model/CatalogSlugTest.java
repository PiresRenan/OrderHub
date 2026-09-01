package io.github.piresrenan.orderhub.catalog.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.Test;

class CatalogSlugTest {

    @Test
    void preservesValidSlugExactly() {
        var slug = CatalogSlug.of("Summer_Sale-2026");

        assertThat(slug.value())
                .isEqualTo("Summer_Sale-2026");
    }

    @Test
    void acceptsSlugAtMinimumLength() {
        var slug = CatalogSlug.of("a1");

        assertThat(slug.value())
                .isEqualTo("a1");
    }

    @Test
    void acceptsSlugAtMaximumLength() {
        var value = "a".repeat(256);

        var slug = CatalogSlug.of(value);

        assertThat(slug.value())
                .isEqualTo(value);
    }

    @Test
    void rejectsMissingSlug() {
        assertThatThrownBy(() -> CatalogSlug.of(null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Catalog slug is required");
    }

    @Test
    void rejectsBlankSlug() {
        assertThatThrownBy(() -> CatalogSlug.of("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Catalog slug must not be blank");
    }

    @Test
    void rejectsSlugBelowMinimumLength() {
        assertThatThrownBy(() -> CatalogSlug.of("a"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Catalog slug must contain between 2 and 256 characters");
    }

    @Test
    void rejectsSlugAboveMaximumLength() {
        assertThatThrownBy(() -> CatalogSlug.of("a".repeat(257)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Catalog slug must contain between 2 and 256 characters");
    }

    @Test
    void rejectsUnsupportedSlugCharacters() {
        var invalidValues = new String[] {
                "summer sale",
                "summer/sale",
                "summer.sale",
                "summer:sale",
                "café"
        };

        for (var value : invalidValues) {
            assertThatThrownBy(() -> CatalogSlug.of(value))
                    .as("unsupported slug must be rejected: %s", value)
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Catalog slug contains unsupported characters");
        }
    }
}
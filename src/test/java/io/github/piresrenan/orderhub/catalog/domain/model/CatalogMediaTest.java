package io.github.piresrenan.orderhub.catalog.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.junit.jupiter.api.Test;

class CatalogMediaTest {

    @Test
    void createsProductOwnedMedia() {

        var mediaId = UUID.randomUUID();
        var tenantId = UUID.randomUUID();
        var productId = UUID.randomUUID();

        var media = CatalogMedia.forProduct(
                mediaId,
                tenantId,
                productId,
                CatalogMediaType.IMAGE,
                "catalog/products/main-image",
                "Main product image",
                0,
                true);

        assertThat(media.id()).isEqualTo(mediaId);
        assertThat(media.tenantId()).isEqualTo(tenantId);
        assertThat(media.productId()).isEqualTo(productId);
        assertThat(media.variantId()).isNull();
        assertThat(media.mediaType()).isEqualTo(CatalogMediaType.IMAGE);
        assertThat(media.reference()).isEqualTo("catalog/products/main-image");
        assertThat(media.altText()).isEqualTo("Main product image");
        assertThat(media.sortOrder()).isZero();
        assertThat(media.primary()).isTrue();
    }

    @Test
    void createsVariantOwnedMedia() {

        var variantId = UUID.randomUUID();

        var media = CatalogMedia.forVariant(
                UUID.randomUUID(),
                UUID.randomUUID(),
                variantId,
                CatalogMediaType.VIDEO,
                "media/video-01",
                null,
                3,
                false);

        assertThat(media.productId()).isNull();
        assertThat(media.variantId()).isEqualTo(variantId);
        assertThat(media.mediaType()).isEqualTo(CatalogMediaType.VIDEO);
        assertThat(media.altText()).isNull();
    }

    @Test
    void exposesInitialMediaTypes() {

        assertThat(CatalogMediaType.values())
                .containsExactly(
                        CatalogMediaType.IMAGE,
                        CatalogMediaType.VIDEO,
                        CatalogMediaType.DOCUMENT,
                        CatalogMediaType.OTHER);
    }

    @Test
    void rejectsMissingIdentityOwnerOrType() {

        var tenantId = UUID.randomUUID();
        var productId = UUID.randomUUID();

        assertThatThrownBy(() -> CatalogMedia.forProduct(
                null, tenantId, productId, CatalogMediaType.IMAGE, "ref", null, 0, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Catalog media id is required");

        assertThatThrownBy(() -> CatalogMedia.forProduct(
                UUID.randomUUID(), null, productId, CatalogMediaType.IMAGE, "ref", null, 0, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Catalog media tenant id is required");

        assertThatThrownBy(() -> CatalogMedia.forProduct(
                UUID.randomUUID(), tenantId, null, CatalogMediaType.IMAGE, "ref", null, 0, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Catalog media product id is required");

        assertThatThrownBy(() -> CatalogMedia.forProduct(
                UUID.randomUUID(), tenantId, productId, null, "ref", null, 0, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Catalog media type is required");
    }

    @Test
    void rejectsInvalidReference() {

        var tenantId = UUID.randomUUID();
        var productId = UUID.randomUUID();

        assertThatThrownBy(() -> CatalogMedia.forProduct(
                UUID.randomUUID(), tenantId, productId, CatalogMediaType.IMAGE, " ", null, 0, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Catalog media reference must not be blank");

        assertThatThrownBy(() -> CatalogMedia.forProduct(
                UUID.randomUUID(), tenantId, productId, CatalogMediaType.IMAGE, " ref ", null, 0, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Catalog media reference must not contain surrounding whitespace");

        assertThatThrownBy(() -> CatalogMedia.forProduct(
                UUID.randomUUID(), tenantId, productId, CatalogMediaType.IMAGE, "a\nb", null, 0, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Catalog media reference must not contain control characters");

        assertThatThrownBy(() -> CatalogMedia.forProduct(
                UUID.randomUUID(), tenantId, productId, CatalogMediaType.IMAGE, "x".repeat(2049), null, 0, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Catalog media reference must not exceed 2048 characters");
    }

    @Test
    void normalizesOptionalAltTextAndRejectsInvalidAltText() {

        var media = CatalogMedia.forProduct(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                CatalogMediaType.IMAGE,
                "ref",
                "  Accessible description  ",
                0,
                false);

        assertThat(media.altText()).isEqualTo("Accessible description");

        assertThatThrownBy(() -> CatalogMedia.forProduct(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                CatalogMediaType.IMAGE, "ref", "   ", 0, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Catalog media alt text must not be blank");

        assertThatThrownBy(() -> CatalogMedia.forProduct(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                CatalogMediaType.IMAGE, "ref", "x".repeat(513), 0, false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Catalog media alt text must not exceed 512 characters");
    }

    @Test
    void rejectsNegativeSortOrder() {

        assertThatThrownBy(() -> CatalogMedia.forVariant(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                CatalogMediaType.OTHER,
                "storage-key",
                null,
                -1,
                false))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Catalog media sort order must not be negative");
    }
}
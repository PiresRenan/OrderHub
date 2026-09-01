package io.github.piresrenan.orderhub.catalog.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class ProductVariantCommercialModelTest {

    @Test
    void createsDraftVariantWithCommercialMetadata() {

        var attributes = List.of(
                ProductVariantAttribute.of("color", "black"),
                ProductVariantAttribute.of("screen.size", "15.6"));

        var variant = ProductVariant.create(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "NB-PRO-01",
                "  Notebook Pro / Black  ",
                "4006381333931",
                "NB-PRO-BLK",
                attributes);

        assertThat(variant.status()).isEqualTo(ProductVariantStatus.DRAFT);
        assertThat(variant.displayName()).isEqualTo("Notebook Pro / Black");
        assertThat(variant.gtin()).isEqualTo("4006381333931");
        assertThat(variant.mpn()).isEqualTo("NB-PRO-BLK");
        assertThat(variant.attributes()).containsExactlyElementsOf(attributes);
        assertThat(variant.isSellable()).isFalse();
    }

    @Test
    void commercialMetadataIsOptional() {

        var variant = ProductVariant.create(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "SKU", null, null, null, List.of());

        assertThat(variant.displayName()).isNull();
        assertThat(variant.gtin()).isNull();
        assertThat(variant.mpn()).isNull();
        assertThat(variant.attributes()).isEmpty();
    }

    @Test
    void acceptsAllSupportedGtinLengthsAndPreservesLeadingZeroes() {

        var values = List.of(
                "96385074",
                "036000291452",
                "4006381333931",
                "10012345678902",
                "00012345600012");

        for (var gtin : values) {
            var variant = variantWithGtin(gtin);
            assertThat(variant.gtin()).isEqualTo(gtin);
        }
    }

    @Test
    void rejectsInvalidGtinLengthCharactersAndCheckDigit() {

        assertThatThrownBy(() -> variantWithGtin("1234567"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Product variant GTIN must contain 8, 12, 13 or 14 digits");

        assertThatThrownBy(() -> variantWithGtin("40063813339A1"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Product variant GTIN must contain only decimal digits");

        assertThatThrownBy(() -> variantWithGtin("4006381333932"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Product variant GTIN has an invalid GS1 check digit");
    }

    @Test
    void validatesOptionalDisplayName() {

        assertThatThrownBy(() -> variantWithDisplayName("   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Product variant display name must not be blank");

        assertThatThrownBy(() -> variantWithDisplayName("x".repeat(161)))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Product variant display name must not exceed 160 characters");

        assertThatThrownBy(() -> variantWithDisplayName("Name\nOther"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Product variant display name must not contain control characters");
    }

    @Test
    void validatesMpnWithoutCanonicalizingIt() {

        var variant = ProductVariant.create(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "SKU", null, null, "Part.No/A-01", List.of());

        assertThat(variant.mpn()).isEqualTo("Part.No/A-01");

        assertThatThrownBy(() -> ProductVariant.create(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "SKU", null, null, " MPN ", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Product variant MPN must not contain surrounding whitespace");

        assertThatThrownBy(() -> ProductVariant.create(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "SKU", null, null, "x".repeat(71), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Product variant MPN must not exceed 70 characters");
    }

    @Test
    void rejectsDuplicateAttributeKeysButComparisonIsCaseSensitive() {

        var duplicate = List.of(
                ProductVariantAttribute.of("color", "black"),
                ProductVariantAttribute.of("color", "white"));

        assertThatThrownBy(() -> variantWithAttributes(duplicate))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Product variant attribute keys must be unique");

        var caseDistinct = List.of(
                ProductVariantAttribute.of("color", "black"),
                ProductVariantAttribute.of("Color", "display label"));

        assertThat(variantWithAttributes(caseDistinct).attributes())
                .containsExactlyElementsOf(caseDistinct);
    }

    @Test
    void defensivelyCopiesAttributes() {

        var attributes = new ArrayList<ProductVariantAttribute>();
        attributes.add(ProductVariantAttribute.of("color", "black"));

        var variant = variantWithAttributes(attributes);

        attributes.add(ProductVariantAttribute.of("size", "large"));

        assertThat(variant.attributes()).hasSize(1);
        assertThatThrownBy(() -> variant.attributes().add(
                ProductVariantAttribute.of("memory", "32GB")))
                .isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    void followsExplicitSellabilityLifecycle() {

        var draft = ProductVariant.create(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "SKU", null, null, null, List.of());

        var active = draft.activate();
        var inactive = active.deactivate();
        var reactivated = inactive.activate();
        var archived = reactivated.archive();

        assertThat(draft.status()).isEqualTo(ProductVariantStatus.DRAFT);
        assertThat(active.status()).isEqualTo(ProductVariantStatus.ACTIVE);
        assertThat(inactive.status()).isEqualTo(ProductVariantStatus.INACTIVE);
        assertThat(reactivated.status()).isEqualTo(ProductVariantStatus.ACTIVE);
        assertThat(archived.status()).isEqualTo(ProductVariantStatus.ARCHIVED);

        assertThat(draft.isSellable()).isFalse();
        assertThat(active.isSellable()).isTrue();
        assertThat(inactive.isSellable()).isFalse();
        assertThat(archived.isSellable()).isFalse();
    }

    @Test
    void archivedVariantCannotReturnToCommercialLifecycle() {

        var archived = ProductVariant.create(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "SKU", null, null, null, List.of())
                .archive();

        assertThatThrownBy(archived::activate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Archived product variant cannot be activated");

        assertThatThrownBy(archived::deactivate)
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Archived product variant cannot be deactivated");
    }

    private static ProductVariant variantWithGtin(String gtin) {
        return ProductVariant.create(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "SKU", null, gtin, null, List.of());
    }

    private static ProductVariant variantWithDisplayName(String displayName) {
        return ProductVariant.create(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "SKU", displayName, null, null, List.of());
    }

    private static ProductVariant variantWithAttributes(
            List<ProductVariantAttribute> attributes) {

        return ProductVariant.create(
                UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(),
                "SKU", null, null, null, attributes);
    }
}
package io.github.piresrenan.orderhub.catalog.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class ProductVariantTest {

    private static final int MAX_SKU_CODE_POINTS = 64;

    @Test
    void createsSellableVariantWithTenantScopedIdentityAndSku() {
        // Why: Orders and Inventory must reference a concrete sellable unit rather
        // than the abstract merchandising Product.
        // Covers: ProductVariant identity, Tenant ownership, Product ownership and SKU.
        // Prevents: stock or commerce operations being attached to the wrong
        // abstraction or losing Tenant ownership.

        var variantId = UUID.randomUUID();
        var tenantId = UUID.randomUUID();
        var productId = UUID.randomUUID();

        var variant = ProductVariant.create(
                variantId,
                tenantId,
                productId,
                "SKU-001");

        assertThat(variant.id())
                .isEqualTo(variantId);

        assertThat(variant.tenantId())
                .isEqualTo(tenantId);

        assertThat(variant.productId())
                .isEqualTo(productId);

        assertThat(variant.sku())
                .isEqualTo("SKU-001");
    }

    @Test
    void rejectsMissingVariantId() {
        // Why: a sellable Variant without identity cannot be referenced safely by
        // Orders, Inventory, pricing or persistence.
        // Covers: mandatory ProductVariant identity.
        // Prevents: null persistence keys and ambiguous sellable-unit references.

        assertThatThrownBy(() -> ProductVariant.create(
                null,
                UUID.randomUUID(),
                UUID.randomUUID(),
                "SKU-001"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Product variant id is required");
    }

    @Test
    void rejectsMissingTenantId() {
        // Why: every Variant must have an explicit Tenant owner.
        // Covers: tenant ownership at the Catalog boundary.
        // Prevents: tenant-neutral sellable units entering Catalog or Inventory.

        assertThatThrownBy(() -> ProductVariant.create(
                UUID.randomUUID(),
                null,
                UUID.randomUUID(),
                "SKU-001"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Product variant tenant id is required");
    }

    @Test
    void rejectsMissingProductId() {
        // Why: every Variant belongs to one Product merchandising concept.
        // Covers: mandatory Product parent identity.
        // Prevents: orphan Variants acquiring pricing, inventory or Orders.

        assertThatThrownBy(() -> ProductVariant.create(
                UUID.randomUUID(),
                UUID.randomUUID(),
                null,
                "SKU-001"))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Product variant product id is required");
    }

    @Test
    void rejectsMissingSku() {
        // Why: a sellable Variant needs one operational commercial identity.
        // Covers: mandatory SKU presence.
        // Prevents: Variants that external commerce and Inventory systems cannot
        // address deterministically.

        assertThatThrownBy(() -> ProductVariant.create(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                null))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Product variant SKU is required");
    }

    @Test
    void rejectsBlankSku() {
        // Why: ordinary whitespace does not constitute a commercial identifier.
        // Covers: semantic SKU presence.
        // Prevents: visually empty SKUs.

        assertThatThrownBy(() -> ProductVariant.create(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "   "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Product variant SKU must not be blank");
    }

    @Test
    void rejectsSkuContainingOnlyUnicodeSpace() {
        // Why: Unicode space characters such as non-breaking space can appear
        // populated while remaining visually blank.
        // Covers: Unicode-aware blank detection.
        // Prevents: invisible SKU identities bypassing ordinary String.isBlank()
        // behavior.

        var nonBreakingSpace = "\u00A0";

        assertThatThrownBy(() -> ProductVariant.create(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                nonBreakingSpace))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Product variant SKU must not be blank");
    }

    @Test
    void rejectsSkuWithSurroundingWhitespace() {
        // Why: silently trimming SKU would mutate an external identity while storing
        // it verbatim would create ambiguous identifiers.
        // Covers: canonical SKU boundary representation.
        // Prevents: "SKU-001" and " SKU-001 " becoming different identities.

        assertThatThrownBy(() -> ProductVariant.create(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                " SKU-001 "))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Product variant SKU must not contain surrounding whitespace");
    }

    @Test
    void rejectsSkuWithUnicodeSurroundingWhitespace() {
        // Why: not every Unicode spacing character is handled identically by
        // String.strip().
        // Covers: Unicode-aware SKU boundary validation.
        // Prevents: visually indistinguishable keys differing only by Unicode
        // spacing characters.

        var figureSpace = "\u2007";

        assertThatThrownBy(() -> ProductVariant.create(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                figureSpace + "SKU-001" + figureSpace))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Product variant SKU must not contain surrounding whitespace");
    }

    @Test
    void preservesSkuCaseAndInternalContentExactly() {
        // Why: SKU is an externally meaningful machine-readable identifier.
        // Covers: exact case/content preservation.
        // Prevents: automatic uppercase, lowercase, trimming or arbitrary syntax
        // rewriting that could break integrations.

        var sku = "AbC 123/xy_z.9:rev2";

        var variant = ProductVariant.create(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                sku);

        assertThat(variant.sku())
                .isEqualTo(sku);
    }

    @Test
    void acceptsSkuAtMaximumLength() {
        // Why: the upper boundary must be inclusive and explicitly tested.
        // Covers: exactly 64 Unicode code points.
        // Prevents: off-by-one validation rejecting a valid maximum-length SKU.

        var sku = "A".repeat(MAX_SKU_CODE_POINTS);

        var variant = ProductVariant.create(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                sku);

        assertThat(variant.sku())
                .isEqualTo(sku);
    }

    @Test
    void rejectsSkuAboveMaximumLength() {
        // Why: bounded machine identifiers improve interoperability with commerce
        // systems whose SKU contracts impose finite limits.
        // Covers: 65-code-point SKU rejection.
        // Prevents: accepting identifiers that exceed the Catalog contract.

        var sku = "A".repeat(MAX_SKU_CODE_POINTS + 1);

        assertThatThrownBy(() -> ProductVariant.create(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                sku))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Product variant SKU must not exceed 64 characters");
    }

    @Test
    void measuresSkuLengthByUnicodeCodePoint() {
        // Why: String.length() counts UTF-16 code units and can incorrectly count
        // supplementary Unicode characters twice.
        // Covers: consistent code-point semantics at both sides of the boundary.
        // Prevents: Java rejecting a 64-character logical identifier merely because
        // some characters require surrogate pairs.

        var supplementaryLetter =
                new String(Character.toChars(0x10400));

        var maximumSku =
                supplementaryLetter.repeat(MAX_SKU_CODE_POINTS);

        var overLimitSku =
                supplementaryLetter.repeat(MAX_SKU_CODE_POINTS + 1);

        var variant = ProductVariant.create(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                maximumSku);

        assertThat(variant.sku())
                .isEqualTo(maximumSku);

        assertThatThrownBy(() -> ProductVariant.create(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                overLimitSku))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Product variant SKU must not exceed 64 characters");
    }

    @Test
    void rejectsIsoControlCharactersInsideSku() {
        // Why: control characters can corrupt logs, CSV interchange, terminal
        // rendering and external-system identifiers while remaining hard to inspect.
        // Covers: control characters anywhere inside an otherwise populated SKU.
        // Prevents: invisible operational identifiers and unsafe interchange values.

        var invalidSkus = List.of(
                "SKU-" + Character.toString(0) + "001",
                "SKU-" + Character.toString(10) + "001",
                "SKU-" + Character.toString(127) + "001");

        for (var sku : invalidSkus) {
            assertThatThrownBy(() -> ProductVariant.create(
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    UUID.randomUUID(),
                    sku))
                    .as("SKU containing ISO control code point must be rejected")
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessage("Product variant SKU must not contain control characters");
        }
    }
}
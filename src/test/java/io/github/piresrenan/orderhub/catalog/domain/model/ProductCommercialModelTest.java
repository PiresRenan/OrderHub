package io.github.piresrenan.orderhub.catalog.domain.model;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

class ProductCommercialModelTest {

    @Test
    void draftProductMayExistWithoutVariantsAndPreservesNormalizedBrand() {

        var product = Product.create(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Notebook Pro",
                "notebook-pro",
                "Portable workstation",
                "  Framework  ",
                List.of());

        assertThat(product.status()).isEqualTo(ProductStatus.DRAFT);
        assertThat(product.brand()).isEqualTo("Framework");
    }

    @Test
    void brandIsOptional() {

        var product = Product.create(
                UUID.randomUUID(),
                UUID.randomUUID(),
                "Product",
                "product",
                null,
                null,
                List.of());

        assertThat(product.brand()).isNull();
    }

    @Test
    void rejectsInvalidBrand() {

        assertThatThrownBy(() -> Product.create(
                UUID.randomUUID(), UUID.randomUUID(), "Product", "product", null,
                "   ", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Product brand must not be blank");

        assertThatThrownBy(() -> Product.create(
                UUID.randomUUID(), UUID.randomUUID(), "Product", "product", null,
                "x".repeat(121), List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Product brand must not exceed 120 characters");

        assertThatThrownBy(() -> Product.create(
                UUID.randomUUID(), UUID.randomUUID(), "Product", "product", null,
                "Brand\nName", List.of()))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessage("Product brand must not contain control characters");
    }

    @Test
    void activatesOnlyWhenAtLeastOneActiveVariantBelongsToProductAndTenant() {

        var productId = UUID.randomUUID();
        var tenantId = UUID.randomUUID();

        var product = Product.create(
                productId, tenantId, "Product", "product", null, null, List.of());

        var activeVariant = ProductVariant.create(
                UUID.randomUUID(), tenantId, productId, "SKU-1",
                null, null, null, List.of())
                .activate();

        var activeProduct = product.activate(List.of(activeVariant));

        assertThat(activeProduct.status()).isEqualTo(ProductStatus.ACTIVE);
        assertThat(activeProduct.id()).isEqualTo(productId);
        assertThat(activeProduct.tenantId()).isEqualTo(tenantId);
    }

    @Test
    void rejectsActivationWithoutEligibleActiveVariant() {

        var productId = UUID.randomUUID();
        var tenantId = UUID.randomUUID();

        var product = Product.create(
                productId, tenantId, "Product", "product", null, null, List.of());

        var draftVariant = ProductVariant.create(
                UUID.randomUUID(), tenantId, productId, "SKU-1",
                null, null, null, List.of());

        assertThatThrownBy(() -> product.activate(List.of()))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Product activation requires at least one active variant");

        assertThatThrownBy(() -> product.activate(List.of(draftVariant)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Product activation requires at least one active variant");
    }

    @Test
    void foreignTenantOrProductVariantDoesNotSatisfyActivation() {

        var productId = UUID.randomUUID();
        var tenantId = UUID.randomUUID();

        var product = Product.create(
                productId, tenantId, "Product", "product", null, null, List.of());

        var foreignVariant = ProductVariant.create(
                UUID.randomUUID(),
                UUID.randomUUID(),
                UUID.randomUUID(),
                "SKU-FOREIGN",
                null, null, null, List.of())
                .activate();

        assertThatThrownBy(() -> product.activate(List.of(foreignVariant)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessage("Product activation requires at least one active variant");
    }
}
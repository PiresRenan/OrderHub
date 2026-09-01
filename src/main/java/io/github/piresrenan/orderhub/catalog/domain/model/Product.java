package io.github.piresrenan.orderhub.catalog.domain.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/**
 * Tenant-owned merchandising concept in the Catalog.
 */
public final class Product {

    private static final int MAX_BRAND_CODE_POINTS = 120;

    private final UUID id;
    private final UUID tenantId;
    private final String name;
    private final CatalogSlug slug;
    private final String description;
    private final String brand;
    private final List<UUID> categoryIds;
    private final ProductStatus status;

    private Product(
            UUID id,
            UUID tenantId,
            String name,
            String slug,
            String description,
            String brand,
            Collection<UUID> categoryIds,
            ProductStatus status) {

        if (id == null) {
            throw new IllegalArgumentException(
                    "Product id is required");
        }

        if (tenantId == null) {
            throw new IllegalArgumentException(
                    "Product tenant id is required");
        }

        if (name == null) {
            throw new IllegalArgumentException(
                    "Product name is required");
        }

        if (isUnicodeBlank(name)) {
            throw new IllegalArgumentException(
                    "Product name must not be blank");
        }

        if (status == null) {
            throw new IllegalArgumentException(
                    "Product status is required");
        }

        this.id = id;
        this.tenantId = tenantId;
        this.name = stripSurroundingUnicodeWhitespace(name);
        this.slug = CatalogSlug.of(slug);
        this.description = description;
        this.brand = normalizeBrand(brand);
        this.categoryIds = validateAndCopyCategoryIds(categoryIds);
        this.status = status;
    }

    public static Product create(
            UUID id,
            UUID tenantId,
            String name,
            String slug,
            String description,
            Collection<UUID> categoryIds) {

        return create(
                id,
                tenantId,
                name,
                slug,
                description,
                null,
                categoryIds);
    }

    public static Product create(
            UUID id,
            UUID tenantId,
            String name,
            String slug,
            String description,
            String brand,
            Collection<UUID> categoryIds) {

        return new Product(
                id,
                tenantId,
                name,
                slug,
                description,
                brand,
                categoryIds,
                ProductStatus.DRAFT);
    }

    public static Product rehydrate(
            UUID id,
            UUID tenantId,
            String name,
            String slug,
            String description,
            Collection<UUID> categoryIds,
            ProductStatus status) {

        return rehydrate(
                id,
                tenantId,
                name,
                slug,
                description,
                null,
                categoryIds,
                status);
    }

    public static Product rehydrate(
            UUID id,
            UUID tenantId,
            String name,
            String slug,
            String description,
            String brand,
            Collection<UUID> categoryIds,
            ProductStatus status) {

        return new Product(
                id,
                tenantId,
                name,
                slug,
                description,
                brand,
                categoryIds,
                status);
    }

    public Product activate(
            Collection<ProductVariant> variants) {

        if (status == ProductStatus.ARCHIVED) {
            throw new IllegalStateException(
                    "Archived product cannot be activated");
        }

        if (variants == null) {
            throw new IllegalArgumentException(
                    "Product variants are required");
        }

        var hasEligibleVariant = false;

        for (var variant : variants) {

            if (
                    variant != null
                    && tenantId.equals(variant.tenantId())
                    && id.equals(variant.productId())
                    && variant.isSellable()) {

                hasEligibleVariant = true;
                break;
            }
        }

        if (!hasEligibleVariant) {
            throw new IllegalStateException(
                    "Product activation requires at least one active variant");
        }

        return new Product(
                id,
                tenantId,
                name,
                slug.value(),
                description,
                brand,
                categoryIds,
                ProductStatus.ACTIVE);
    }

    private static String normalizeBrand(
            String brand) {

        if (brand == null) {
            return null;
        }

        if (isUnicodeBlank(brand)) {
            throw new IllegalArgumentException(
                    "Product brand must not be blank");
        }

        if (containsIsoControlCharacter(brand)) {
            throw new IllegalArgumentException(
                    "Product brand must not contain control characters");
        }

        var normalized = stripSurroundingUnicodeWhitespace(brand);

        if (normalized.codePointCount(0, normalized.length()) > MAX_BRAND_CODE_POINTS) {
            throw new IllegalArgumentException(
                    "Product brand must not exceed 120 characters");
        }

        return normalized;
    }

    private static List<UUID> validateAndCopyCategoryIds(
            Collection<UUID> categoryIds) {

        if (categoryIds == null) {
            throw new IllegalArgumentException(
                    "Product category ids are required");
        }

        var validatedCategoryIds =
                new ArrayList<UUID>(categoryIds.size());

        var uniqueCategoryIds =
                new HashSet<UUID>();

        for (var categoryId : categoryIds) {

            if (categoryId == null) {
                throw new IllegalArgumentException(
                        "Product category ids must not contain null values");
            }

            if (!uniqueCategoryIds.add(categoryId)) {
                throw new IllegalArgumentException(
                        "Product category ids must not contain duplicates");
            }

            validatedCategoryIds.add(categoryId);
        }

        return List.copyOf(validatedCategoryIds);
    }

    private static boolean isUnicodeBlank(
            String value) {

        return value.codePoints()
                .allMatch(Product::isUnicodeWhitespace);
    }

    private static String stripSurroundingUnicodeWhitespace(
            String value) {

        var start = 0;
        var end = value.length();

        while (start < end) {
            var codePoint = value.codePointAt(start);

            if (!isUnicodeWhitespace(codePoint)) {
                break;
            }

            start += Character.charCount(codePoint);
        }

        while (end > start) {
            var codePoint = value.codePointBefore(end);

            if (!isUnicodeWhitespace(codePoint)) {
                break;
            }

            end -= Character.charCount(codePoint);
        }

        return value.substring(start, end);
    }

    private static boolean isUnicodeWhitespace(
            int codePoint) {

        return Character.isWhitespace(codePoint)
                || Character.isSpaceChar(codePoint);
    }

    private static boolean containsIsoControlCharacter(
            String value) {

        return value.codePoints()
                .anyMatch(Character::isISOControl);
    }

    public UUID id() {
        return id;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public String name() {
        return name;
    }

    public String slug() {
        return slug.value();
    }

    public String description() {
        return description;
    }

    public String brand() {
        return brand;
    }

    public List<UUID> categoryIds() {
        return categoryIds;
    }

    public ProductStatus status() {
        return status;
    }
}
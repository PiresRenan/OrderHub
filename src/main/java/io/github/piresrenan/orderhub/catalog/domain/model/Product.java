package io.github.piresrenan.orderhub.catalog.domain.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/**
 * Tenant-owned merchandising concept in the Catalog.
 *
 * <p>
 * Product owns commercial presentation and classification. Concrete sellable
 * identities remain ProductVariant instances and reference Product by id.
 * </p>
 *
 * <p>
 * Products are created as DRAFT. The future activation use case will coordinate
 * Product with its related Variants and any additional commercial requirements
 * before allowing it to become ACTIVE.
 * </p>
 */
public final class Product {

    private final UUID id;
    private final UUID tenantId;
    private final String name;
    private final CatalogSlug slug;
    private final String description;
    private final List<UUID> categoryIds;
    private final ProductStatus status;

    private Product(
            UUID id,
            UUID tenantId,
            String name,
            String slug,
            String description,
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
        this.categoryIds =
                validateAndCopyCategoryIds(categoryIds);
        this.status = status;
    }

    /**
     * Creates a new Product at the beginning of its commercial lifecycle.
     *
     * <p>
     * New Products always start as DRAFT. Lifecycle transitions remain separate
     * business operations and are intentionally not exposed by OH-011 yet.
     * </p>
     */
    public static Product create(
            UUID id,
            UUID tenantId,
            String name,
            String slug,
            String description,
            Collection<UUID> categoryIds) {

        return new Product(
                id,
                tenantId,
                name,
                slug,
                description,
                categoryIds,
                ProductStatus.DRAFT);
    }

    /**
     * Reconstructs a Product from persisted state.
     *
     * <p>
     * Rehydration is distinct from creation because persisted Products may
     * legitimately already be ACTIVE or ARCHIVED. The same structural domain
     * invariants continue to be validated while the previously persisted
     * lifecycle state is restored exactly.
     * </p>
     */
    public static Product rehydrate(
            UUID id,
            UUID tenantId,
            String name,
            String slug,
            String description,
            Collection<UUID> categoryIds,
            ProductStatus status) {

        return new Product(
                id,
                tenantId,
                name,
                slug,
                description,
                categoryIds,
                status);
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

    /**
     * Returns an immutable snapshot of Category assignments captured when the
     * Product was created or rehydrated.
     */
    public List<UUID> categoryIds() {
        return categoryIds;
    }

    public ProductStatus status() {
        return status;
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

        return List.copyOf(
                validatedCategoryIds);
    }

    private static boolean isUnicodeBlank(
            String value) {

        return value.codePoints()
                .allMatch(Product::isUnicodeWhitespace);
    }

    private static String stripSurroundingUnicodeWhitespace(
            String value) {

        var start =
                0;

        var end =
                value.length();

        while (start < end) {
            var codePoint =
                    value.codePointAt(start);

            if (!isUnicodeWhitespace(codePoint)) {
                break;
            }

            start +=
                    Character.charCount(codePoint);
        }

        while (end > start) {
            var codePoint =
                    value.codePointBefore(end);

            if (!isUnicodeWhitespace(codePoint)) {
                break;
            }

            end -=
                    Character.charCount(codePoint);
        }

        return value.substring(
                start,
                end);
    }

    private static boolean isUnicodeWhitespace(
            int codePoint) {

        return Character.isWhitespace(codePoint)
                || Character.isSpaceChar(codePoint);
    }
}
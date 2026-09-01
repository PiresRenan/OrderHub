package io.github.piresrenan.orderhub.catalog.domain.model;

import java.util.UUID;

/**
 * Tenant-owned classification node in the Catalog hierarchy.
 *
 * <p>
 * A Category can be a root node or reference one parent Category. Tree
 * integrity across multiple Category instances, including cross-Tenant parent
 * validation and cycle detection beyond direct self-parenting, belongs to the
 * application/persistence boundary where the complete hierarchy is available.
 * </p>
 */
public final class Category {

    private final UUID id;
    private final UUID tenantId;
    private final UUID parentCategoryId;
    private final String name;
    private final CatalogSlug slug;
    private final String description;

    private Category(
            UUID id,
            UUID tenantId,
            UUID parentCategoryId,
            String name,
            String slug,
            String description) {

        if (id == null) {
            throw new IllegalArgumentException(
                    "Category id is required");
        }

        if (tenantId == null) {
            throw new IllegalArgumentException(
                    "Category tenant id is required");
        }

        if (name == null) {
            throw new IllegalArgumentException(
                    "Category name is required");
        }

        if (isUnicodeBlank(name)) {
            throw new IllegalArgumentException(
                    "Category name must not be blank");
        }

        if (parentCategoryId != null
                && parentCategoryId.equals(id)) {

            throw new IllegalArgumentException(
                    "Category must not be its own parent");
        }

        this.id = id;
        this.tenantId = tenantId;
        this.parentCategoryId = parentCategoryId;
        this.name = stripSurroundingUnicodeWhitespace(name);
        this.slug = CatalogSlug.of(slug);
        this.description = description;
    }

    public static Category create(
            UUID id,
            UUID tenantId,
            UUID parentCategoryId,
            String name,
            String slug,
            String description) {

        return new Category(
                id,
                tenantId,
                parentCategoryId,
                name,
                slug,
                description);
    }

    public UUID id() {
        return id;
    }

    public UUID tenantId() {
        return tenantId;
    }

    public UUID parentCategoryId() {
        return parentCategoryId;
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

    private static boolean isUnicodeBlank(String value) {

        return value.codePoints()
                .allMatch(Category::isUnicodeWhitespace);
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
package io.github.piresrenan.orderhub.catalog.domain.model;

import java.util.UUID;

/**
 * Metadata reference attached to exactly one Product or ProductVariant.
 */
public final class CatalogMedia {

    private static final int MAX_REFERENCE_CODE_POINTS = 2048;
    private static final int MAX_ALT_TEXT_CODE_POINTS = 512;

    private final UUID id;
    private final UUID tenantId;
    private final UUID productId;
    private final UUID variantId;
    private final CatalogMediaType mediaType;
    private final String reference;
    private final String altText;
    private final int sortOrder;
    private final boolean primary;

    private CatalogMedia(
            UUID id,
            UUID tenantId,
            UUID productId,
            UUID variantId,
            CatalogMediaType mediaType,
            String reference,
            String altText,
            int sortOrder,
            boolean primary) {

        if (id == null) {
            throw new IllegalArgumentException(
                    "Catalog media id is required");
        }

        if (tenantId == null) {
            throw new IllegalArgumentException(
                    "Catalog media tenant id is required");
        }

        if ((productId == null) == (variantId == null)) {
            throw new IllegalArgumentException(
                    "Catalog media must belong to exactly one owner");
        }

        if (mediaType == null) {
            throw new IllegalArgumentException(
                    "Catalog media type is required");
        }

        validateReference(reference);

        if (sortOrder < 0) {
            throw new IllegalArgumentException(
                    "Catalog media sort order must not be negative");
        }

        this.id = id;
        this.tenantId = tenantId;
        this.productId = productId;
        this.variantId = variantId;
        this.mediaType = mediaType;
        this.reference = reference;
        this.altText = normalizeAltText(altText);
        this.sortOrder = sortOrder;
        this.primary = primary;
    }

    public static CatalogMedia forProduct(
            UUID id,
            UUID tenantId,
            UUID productId,
            CatalogMediaType mediaType,
            String reference,
            String altText,
            int sortOrder,
            boolean primary) {

        if (productId == null) {
            throw new IllegalArgumentException(
                    "Catalog media product id is required");
        }

        return new CatalogMedia(
                id,
                tenantId,
                productId,
                null,
                mediaType,
                reference,
                altText,
                sortOrder,
                primary);
    }

    public static CatalogMedia forVariant(
            UUID id,
            UUID tenantId,
            UUID variantId,
            CatalogMediaType mediaType,
            String reference,
            String altText,
            int sortOrder,
            boolean primary) {

        if (variantId == null) {
            throw new IllegalArgumentException(
                    "Catalog media variant id is required");
        }

        return new CatalogMedia(
                id,
                tenantId,
                null,
                variantId,
                mediaType,
                reference,
                altText,
                sortOrder,
                primary);
    }

    private static void validateReference(
            String reference) {

        if (reference == null) {
            throw new IllegalArgumentException(
                    "Catalog media reference is required");
        }

        if (isUnicodeBlank(reference)) {
            throw new IllegalArgumentException(
                    "Catalog media reference must not be blank");
        }

        if (hasSurroundingUnicodeWhitespace(reference)) {
            throw new IllegalArgumentException(
                    "Catalog media reference must not contain surrounding whitespace");
        }

        if (containsIsoControlCharacter(reference)) {
            throw new IllegalArgumentException(
                    "Catalog media reference must not contain control characters");
        }

        if (reference.codePointCount(0, reference.length()) > MAX_REFERENCE_CODE_POINTS) {
            throw new IllegalArgumentException(
                    "Catalog media reference must not exceed 2048 characters");
        }
    }

    private static String normalizeAltText(
            String altText) {

        if (altText == null) {
            return null;
        }

        if (isUnicodeBlank(altText)) {
            throw new IllegalArgumentException(
                    "Catalog media alt text must not be blank");
        }

        if (containsIsoControlCharacter(altText)) {
            throw new IllegalArgumentException(
                    "Catalog media alt text must not contain control characters");
        }

        var normalized =
                stripSurroundingUnicodeWhitespace(altText);

        if (normalized.codePointCount(0, normalized.length()) > MAX_ALT_TEXT_CODE_POINTS) {
            throw new IllegalArgumentException(
                    "Catalog media alt text must not exceed 512 characters");
        }

        return normalized;
    }

    private static boolean isUnicodeBlank(
            String value) {

        return value.codePoints()
                .allMatch(CatalogMedia::isUnicodeWhitespace);
    }

    private static boolean hasSurroundingUnicodeWhitespace(
            String value) {

        return isUnicodeWhitespace(value.codePointAt(0))
                || isUnicodeWhitespace(value.codePointBefore(value.length()));
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

    public UUID productId() {
        return productId;
    }

    public UUID variantId() {
        return variantId;
    }

    public CatalogMediaType mediaType() {
        return mediaType;
    }

    public String reference() {
        return reference;
    }

    public String altText() {
        return altText;
    }

    public int sortOrder() {
        return sortOrder;
    }

    public boolean primary() {
        return primary;
    }
}
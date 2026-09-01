package io.github.piresrenan.orderhub.catalog.domain.model;

/**
 * Explicit commercial attribute owned by one ProductVariant.
 */
public record ProductVariantAttribute(
        String key,
        String value) {

    private static final int MAX_KEY_CODE_POINTS = 64;
    private static final int MAX_VALUE_CODE_POINTS = 256;

    public ProductVariantAttribute {

        validateKey(key);
        validateValue(value);
    }

    public static ProductVariantAttribute of(
            String key,
            String value) {

        return new ProductVariantAttribute(
                key,
                value);
    }

    private static void validateKey(
            String key) {

        if (key == null) {
            throw new IllegalArgumentException(
                    "Variant attribute key is required");
        }

        if (key.codePointCount(0, key.length()) > MAX_KEY_CODE_POINTS) {
            throw new IllegalArgumentException(
                    "Variant attribute key must not exceed 64 characters");
        }

        if (!key.matches("[A-Za-z][A-Za-z0-9._-]*")) {
            throw new IllegalArgumentException(
                    "Variant attribute key has invalid format");
        }
    }

    private static void validateValue(
            String value) {

        if (value == null) {
            throw new IllegalArgumentException(
                    "Variant attribute value is required");
        }

        if (isUnicodeBlank(value)) {
            throw new IllegalArgumentException(
                    "Variant attribute value must not be blank");
        }

        if (hasSurroundingUnicodeWhitespace(value)) {
            throw new IllegalArgumentException(
                    "Variant attribute value must not contain surrounding whitespace");
        }

        if (containsIsoControlCharacter(value)) {
            throw new IllegalArgumentException(
                    "Variant attribute value must not contain control characters");
        }

        if (value.codePointCount(0, value.length()) > MAX_VALUE_CODE_POINTS) {
            throw new IllegalArgumentException(
                    "Variant attribute value must not exceed 256 characters");
        }
    }

    private static boolean isUnicodeBlank(
            String value) {

        return value.codePoints()
                .allMatch(ProductVariantAttribute::isUnicodeWhitespace);
    }

    private static boolean hasSurroundingUnicodeWhitespace(
            String value) {

        return isUnicodeWhitespace(value.codePointAt(0))
                || isUnicodeWhitespace(value.codePointBefore(value.length()));
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
}
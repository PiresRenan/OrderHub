package io.github.piresrenan.orderhub.catalog.domain.model;

import java.util.regex.Pattern;

/**
 * Validated URL-oriented identifier shared by Catalog resources.
 *
 * <p>
 * Slugs are preserved exactly after validation. Generation, transliteration and
 * collision resolution belong to higher application boundaries because those
 * operations require business and locale context.
 * </p>
 */
final class CatalogSlug {

    private static final int MIN_LENGTH = 2;
    private static final int MAX_LENGTH = 256;

    private static final Pattern SUPPORTED_CHARACTERS =
            Pattern.compile("[A-Za-z0-9_-]+");

    private final String value;

    private CatalogSlug(String value) {
        this.value = validate(value);
    }

    static CatalogSlug of(String value) {
        return new CatalogSlug(value);
    }

    String value() {
        return value;
    }

    private static String validate(String value) {

        if (value == null) {
            throw new IllegalArgumentException(
                    "Catalog slug is required");
        }

        if (isUnicodeBlank(value)) {
            throw new IllegalArgumentException(
                    "Catalog slug must not be blank");
        }

        if (value.length() < MIN_LENGTH
                || value.length() > MAX_LENGTH) {

            throw new IllegalArgumentException(
                    "Catalog slug must contain between 2 and 256 characters");
        }

        if (!SUPPORTED_CHARACTERS.matcher(value).matches()) {
            throw new IllegalArgumentException(
                    "Catalog slug contains unsupported characters");
        }

        return value;
    }

    private static boolean isUnicodeBlank(String value) {

        return value.codePoints()
                .allMatch(CatalogSlug::isUnicodeWhitespace);
    }

    private static boolean isUnicodeWhitespace(int codePoint) {

        return Character.isWhitespace(codePoint)
                || Character.isSpaceChar(codePoint);
    }
}
package io.github.piresrenan.orderhub.catalog.domain.model;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;

/**
 * Concrete tenant-owned commercial identity referenced by Orders and Inventory.
 */
public final class ProductVariant {

    private static final int MAX_SKU_CODE_POINTS = 64;
    private static final int MAX_DISPLAY_NAME_CODE_POINTS = 160;
    private static final int MAX_MPN_CODE_POINTS = 70;

    private final UUID id;
    private final UUID tenantId;
    private final UUID productId;
    private final String sku;
    private final String displayName;
    private final String gtin;
    private final String mpn;
    private final List<ProductVariantAttribute> attributes;
    private final ProductVariantStatus status;

    private ProductVariant(
            UUID id,
            UUID tenantId,
            UUID productId,
            String sku,
            String displayName,
            String gtin,
            String mpn,
            Collection<ProductVariantAttribute> attributes,
            ProductVariantStatus status) {

        validateIdentity(id, tenantId, productId);
        validateSku(sku);

        if (status == null) {
            throw new IllegalArgumentException(
                    "Product variant status is required");
        }

        this.id = id;
        this.tenantId = tenantId;
        this.productId = productId;
        this.sku = sku;
        this.displayName = normalizeDisplayName(displayName);
        this.gtin = validateAndReturnGtin(gtin);
        this.mpn = validateAndReturnMpn(mpn);
        this.attributes = validateAndCopyAttributes(attributes);
        this.status = status;
    }

    public static ProductVariant create(
            UUID id,
            UUID tenantId,
            UUID productId,
            String sku) {

        return create(
                id,
                tenantId,
                productId,
                sku,
                null,
                null,
                null,
                List.of());
    }

    public static ProductVariant create(
            UUID id,
            UUID tenantId,
            UUID productId,
            String sku,
            String displayName,
            String gtin,
            String mpn,
            Collection<ProductVariantAttribute> attributes) {

        return new ProductVariant(
                id,
                tenantId,
                productId,
                sku,
                displayName,
                gtin,
                mpn,
                attributes,
                ProductVariantStatus.DRAFT);
    }

    public static ProductVariant rehydrate(
            UUID id,
            UUID tenantId,
            UUID productId,
            String sku,
            String displayName,
            String gtin,
            String mpn,
            Collection<ProductVariantAttribute> attributes,
            ProductVariantStatus status) {

        return new ProductVariant(
                id,
                tenantId,
                productId,
                sku,
                displayName,
                gtin,
                mpn,
                attributes,
                status);
    }

    public ProductVariant activate() {

        if (status == ProductVariantStatus.ARCHIVED) {
            throw new IllegalStateException(
                    "Archived product variant cannot be activated");
        }

        if (status == ProductVariantStatus.ACTIVE) {
            return this;
        }

        return copyWithStatus(ProductVariantStatus.ACTIVE);
    }

    public ProductVariant deactivate() {

        if (status == ProductVariantStatus.ARCHIVED) {
            throw new IllegalStateException(
                    "Archived product variant cannot be deactivated");
        }

        if (status == ProductVariantStatus.INACTIVE) {
            return this;
        }

        if (status != ProductVariantStatus.ACTIVE) {
            throw new IllegalStateException(
                    "Only active product variant can be deactivated");
        }

        return copyWithStatus(ProductVariantStatus.INACTIVE);
    }

    public ProductVariant archive() {

        if (status == ProductVariantStatus.ARCHIVED) {
            return this;
        }

        return copyWithStatus(ProductVariantStatus.ARCHIVED);
    }

    public boolean isSellable() {
        return status == ProductVariantStatus.ACTIVE;
    }

    private ProductVariant copyWithStatus(
            ProductVariantStatus newStatus) {

        return new ProductVariant(
                id,
                tenantId,
                productId,
                sku,
                displayName,
                gtin,
                mpn,
                attributes,
                newStatus);
    }

    private static void validateIdentity(
            UUID id,
            UUID tenantId,
            UUID productId) {

        if (id == null) {
            throw new IllegalArgumentException(
                    "Product variant id is required");
        }

        if (tenantId == null) {
            throw new IllegalArgumentException(
                    "Product variant tenant id is required");
        }

        if (productId == null) {
            throw new IllegalArgumentException(
                    "Product variant product id is required");
        }
    }

    private static void validateSku(
            String sku) {

        if (sku == null) {
            throw new IllegalArgumentException(
                    "Product variant SKU is required");
        }

        if (isUnicodeBlank(sku)) {
            throw new IllegalArgumentException(
                    "Product variant SKU must not be blank");
        }

        if (hasSurroundingUnicodeWhitespace(sku)) {
            throw new IllegalArgumentException(
                    "Product variant SKU must not contain surrounding whitespace");
        }

        if (containsIsoControlCharacter(sku)) {
            throw new IllegalArgumentException(
                    "Product variant SKU must not contain control characters");
        }

        if (sku.codePointCount(0, sku.length()) > MAX_SKU_CODE_POINTS) {
            throw new IllegalArgumentException(
                    "Product variant SKU must not exceed 64 characters");
        }
    }

    private static String normalizeDisplayName(
            String displayName) {

        if (displayName == null) {
            return null;
        }

        if (isUnicodeBlank(displayName)) {
            throw new IllegalArgumentException(
                    "Product variant display name must not be blank");
        }

        if (containsIsoControlCharacter(displayName)) {
            throw new IllegalArgumentException(
                    "Product variant display name must not contain control characters");
        }

        var normalized = stripSurroundingUnicodeWhitespace(displayName);

        if (normalized.codePointCount(0, normalized.length()) > MAX_DISPLAY_NAME_CODE_POINTS) {
            throw new IllegalArgumentException(
                    "Product variant display name must not exceed 160 characters");
        }

        return normalized;
    }

    private static String validateAndReturnGtin(
            String gtin) {

        if (gtin == null) {
            return null;
        }

        var length = gtin.length();

        if (
                length != 8
                && length != 12
                && length != 13
                && length != 14) {

            throw new IllegalArgumentException(
                    "Product variant GTIN must contain 8, 12, 13 or 14 digits");
        }

        var digitsOnly = gtin.chars()
                .allMatch(character -> character >= 48 && character <= 57);

        if (!digitsOnly) {
            throw new IllegalArgumentException(
                    "Product variant GTIN must contain only decimal digits");
        }

        var sum = 0;
        var positionFromRight = 0;

        for (var index = gtin.length() - 2; index >= 0; index--) {

            var digit = gtin.charAt(index) - 48;
            var weight = positionFromRight % 2 == 0 ? 3 : 1;

            sum += digit * weight;
            positionFromRight++;
        }

        var expectedCheckDigit = (10 - (sum % 10)) % 10;
        var actualCheckDigit = gtin.charAt(gtin.length() - 1) - 48;

        if (expectedCheckDigit != actualCheckDigit) {
            throw new IllegalArgumentException(
                    "Product variant GTIN has an invalid GS1 check digit");
        }

        return gtin;
    }

    private static String validateAndReturnMpn(
            String mpn) {

        if (mpn == null) {
            return null;
        }

        if (isUnicodeBlank(mpn)) {
            throw new IllegalArgumentException(
                    "Product variant MPN must not be blank");
        }

        if (hasSurroundingUnicodeWhitespace(mpn)) {
            throw new IllegalArgumentException(
                    "Product variant MPN must not contain surrounding whitespace");
        }

        if (containsIsoControlCharacter(mpn)) {
            throw new IllegalArgumentException(
                    "Product variant MPN must not contain control characters");
        }

        if (mpn.codePointCount(0, mpn.length()) > MAX_MPN_CODE_POINTS) {
            throw new IllegalArgumentException(
                    "Product variant MPN must not exceed 70 characters");
        }

        return mpn;
    }

    private static List<ProductVariantAttribute> validateAndCopyAttributes(
            Collection<ProductVariantAttribute> attributes) {

        if (attributes == null) {
            throw new IllegalArgumentException(
                    "Product variant attributes are required");
        }

        var result =
                new ArrayList<ProductVariantAttribute>(attributes.size());

        var keys =
                new HashSet<String>();

        for (var attribute : attributes) {

            if (attribute == null) {
                throw new IllegalArgumentException(
                        "Product variant attributes must not contain null values");
            }

            if (!keys.add(attribute.key())) {
                throw new IllegalArgumentException(
                        "Product variant attribute keys must be unique");
            }

            result.add(attribute);
        }

        return List.copyOf(result);
    }

    private static boolean isUnicodeBlank(
            String value) {

        return value.codePoints()
                .allMatch(ProductVariant::isUnicodeWhitespace);
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

    public String sku() {
        return sku;
    }

    public String displayName() {
        return displayName;
    }

    public String gtin() {
        return gtin;
    }

    public String mpn() {
        return mpn;
    }

    public List<ProductVariantAttribute> attributes() {
        return attributes;
    }

    public ProductVariantStatus status() {
        return status;
    }
}
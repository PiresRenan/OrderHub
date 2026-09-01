package io.github.piresrenan.orderhub.catalog.domain.model;

import java.util.UUID;

/**
 * Represents one concrete sellable variant inside a tenant-owned Product.
 *
 * <p>
 * ProductVariant is the commercial identity that Orders and Inventory
 * reference.
 * The parent Product represents the merchandising concept, while the Variant
 * represents the independently sellable unit identified operationally by its
 * SKU.
 * </p>
 *
 * <p>
 * The SKU is treated as an externally meaningful machine-readable identifier.
 * Valid SKU values are therefore preserved exactly rather than being silently
 * trimmed, uppercased, lowercased or otherwise canonicalized.
 * </p>
 */
public final class ProductVariant {

    private static final int MAX_SKU_CODE_POINTS = 64;

    private final UUID id;
    private final UUID tenantId;
    private final UUID productId;
    private final String sku;

    private ProductVariant(
            UUID id,
            UUID tenantId,
            UUID productId,
            String sku) {

        validateIdentity(
                id,
                tenantId,
                productId);

        validateSku(sku);

        this.id = id;
        this.tenantId = tenantId;
        this.productId = productId;
        this.sku = sku;
    }

    /**
     * Creates a validated sellable ProductVariant.
     *
     * @param id        variant identity
     * @param tenantId  owning Tenant identity
     * @param productId parent Product identity
     * @param sku       commercial stock-keeping identifier
     * @return validated ProductVariant
     * @throws IllegalArgumentException when any required invariant is violated
     */
    public static ProductVariant create(
            UUID id,
            UUID tenantId,
            UUID productId,
            String sku) {

        return new ProductVariant(
                id,
                tenantId,
                productId,
                sku);
    }

    /**
     * Validates identities required before a Variant can participate in Catalog,
     * Inventory, pricing or Orders.
     */
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

    /**
     * Validates the operational SKU contract.
     *
     * <p>
     * SKU validation intentionally avoids an arbitrary syntax whitelist. Existing
     * commerce and ERP ecosystems use business-defined identifiers containing
     * characters such as spaces, hyphens, underscores, slashes, periods and
     * colons. Restricting the syntax without a concrete integration requirement
     * would unnecessarily reduce interoperability.
     * </p>
     *
     * <p>
     * Instead, the invariant protects properties required for reliable storage
     * and interchange:
     * </p>
     *
     * <ul>
     * <li>the SKU must exist and contain visible/non-spacing content;</li>
     * <li>leading and trailing Unicode whitespace is forbidden;</li>
     * <li>ISO control characters are forbidden;</li>
     * <li>the maximum logical length is 64 Unicode code points;</li>
     * <li>otherwise the supplied representation is preserved exactly.</li>
     * </ul>
     */
    private static void validateSku(String sku) {

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

    /**
     * Determines whether the complete value consists only of Unicode whitespace or
     * Unicode space characters.
     *
     * <p>
     * Character.isWhitespace alone deliberately excludes some Unicode spacing
     * characters, including non-breaking spaces. Character.isSpaceChar covers
     * those Unicode separator categories, so both predicates are required for the
     * Catalog identifier boundary.
     * </p>
     */
    private static boolean isUnicodeBlank(String value) {

        return value.codePoints()
                .allMatch(ProductVariant::isUnicodeWhitespace);
    }

    /**
     * Detects leading or trailing Unicode whitespace without mutating the supplied
     * SKU.
     */
    private static boolean hasSurroundingUnicodeWhitespace(String value) {

        var firstCodePoint = value.codePointAt(0);

        var lastCodePoint = value.codePointBefore(value.length());

        return isUnicodeWhitespace(firstCodePoint)
                || isUnicodeWhitespace(lastCodePoint);
    }

    /**
     * Provides the whitespace definition used consistently by SKU validation.
     */
    private static boolean isUnicodeWhitespace(int codePoint) {

        return Character.isWhitespace(codePoint)
                || Character.isSpaceChar(codePoint);
    }

    /**
     * Detects ISO control code points anywhere inside the SKU.
     */
    private static boolean containsIsoControlCharacter(String value) {

        return value.codePoints()
                .anyMatch(Character::isISOControl);
    }

    /**
     * Returns this sellable Variant's identity.
     */
    public UUID id() {
        return id;
    }

    /**
     * Returns the Tenant that owns this Variant.
     */
    public UUID tenantId() {
        return tenantId;
    }

    /**
     * Returns the parent Product identity.
     */
    public UUID productId() {
        return productId;
    }

    /**
     * Returns the commercial SKU exactly as supplied after validation.
     */
    public String sku() {
        return sku;
    }
}

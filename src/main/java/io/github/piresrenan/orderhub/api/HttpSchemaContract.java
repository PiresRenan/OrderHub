package io.github.piresrenan.orderhub.api;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import io.swagger.v3.oas.models.OpenAPI;
import io.swagger.v3.oas.models.media.Schema;

/** Completes reflected wire projections where Jackson extensions and owner invariants exceed annotation inference. */
final class HttpSchemaContract {
    private static final String JAVA_WHITESPACE = "\\u0009-\\u000D\\u001C-\\u0020\\u1680\\u2000-\\u2006\\u2008-\\u200A\\u2028\\u2029\\u205F\\u3000";
    private static final String CATALOG_WHITESPACE = JAVA_WHITESPACE + "\\u00A0\\u2007\\u202F";
    private static final String CODE_POINT = "(?:[\\uD800-\\uDBFF][\\uDC00-\\uDFFF]|[^\\uD800-\\uDFFF])";
    private HttpSchemaContract() {}

    /** Retains generated fields and references, tightening only verified public constraints rather than copying DTO definitions. */
    static void complete(OpenAPI api, int maxOrderItems) {
        Map<String, Schema> schemas = api.getComponents().getSchemas();
        normalizedText(property(schemas, "AdministrativeNameRequest", "name"), 120, JAVA_WHITESPACE, false, false);
        for (var name : List.of("CatalogProductCreate", "CatalogProductUpdate", "CatalogCategoryCreate", "CatalogCategoryUpdate")) {
            // The application bounds raw Product/Category names before the domain strips whitespace.
            normalizedText(property(schemas, name, "name"), 160, CATALOG_WHITESPACE, false, true);
        }
        for (var name : List.of("CatalogProductCreate", "CatalogProductUpdate")) {
            normalizedText(property(schemas, name, "brand"), 120, CATALOG_WHITESPACE, true, false);
        }
        for (var name : List.of("CatalogVariantCreate", "CatalogVariantUpdate")) {
            normalizedText(property(schemas, name, "displayName"), 160, CATALOG_WHITESPACE, true, false);
        }
        api.getPaths().values().forEach(path -> path.readOperations().forEach(operation -> {
            if (operation.getRequestBody() != null && operation.getRequestBody().getContent() != null) {
                operation.getRequestBody().getContent().values().forEach(media -> closeRequest(media.getSchema(), schemas, new HashSet<>()));
            }
        }));
        for (var name : List.of("StaffProvisioningIssuance", "ExternalIdentityLinkIssuance", "CustomerLinkIssuance")) {
            for (var outcome : List.of("Issued", "Replay")) {
                var schema = schemas.get(name + outcome);
                requireAll(schema);
                schema.setAdditionalProperties(false);
            }
            var credential = property(schemas, name + "Issued", "credential");
            credential.setMinLength(43);
            credential.setMaxLength(43);
            credential.setPattern("^[A-Za-z0-9_-]{43}$");
            credential.setDescription("One-time bearer secret; copy securely from the first response, never log it. Replay cannot recover it.");
            property(schemas, name + "Issued", "expiresAt").setDescription("Absolute expiry with offset; proof validity is checked after database lock acquisition.");
        }
        for (var name : List.of("AdministrativeOrganization", "AdministrativeTenant", "OrganizationTenantSummary")) {
            requireAll(schemas.get(name));
            property(schemas, name, "status").setEnum(List.of("ACTIVE", "SUSPENDED"));
            property(schemas, name, "name").setDescription("Current normalized administrative name.");
        }
        for (var name : List.of("CatalogProductSummary", "CatalogVariantSummary", "ExternalIdentityAccount", "InventoryMovement", "ProductVariantAttribute")) {
            requireAll(schemas.get(name));
        }
        var displayName = property(schemas, "CatalogVariantSummary", "displayName");
        displayName.setTypes(Set.of("string", "null"));
        for (var name : List.of("CatalogVariantCreate", "CatalogVariantUpdate", "CatalogVariantView", "CatalogVariantSummary")) {
            strictText(property(schemas, name, "sku"), 64);
        }
        for (var name : List.of("CatalogVariantCreate", "CatalogVariantUpdate", "CatalogVariantView")) {
            strictText(property(schemas, name, "mpn"), 70);
        }
        for (var name : List.of("CatalogProductSummary", "CatalogVariantSummary")) {
            property(schemas, name, "revision").setMinimum(BigDecimal.ONE);
            property(schemas, name, "revision").setMaximum(BigDecimal.valueOf(Long.MAX_VALUE));
        }
        var key = property(schemas, "ProductVariantAttribute", "key");
        key.setMinLength(1); key.setMaxLength(64); key.setPattern("^[A-Za-z][A-Za-z0-9._-]*$");
        key.setDescription("Unique within a Variant; full key grammar, no surrounding whitespace or controls.");
        var value = property(schemas, "ProductVariantAttribute", "value");
        strictText(value, 256);
        value.setDescription("Nonblank value, at most 256 Unicode code points; no surrounding whitespace or control characters.");
        var delta = property(schemas, "InventoryMovement", "delta");
        delta.setMinimum(BigDecimal.valueOf(Long.MIN_VALUE + 1));
        delta.setMaximum(BigDecimal.valueOf(Long.MAX_VALUE));
        delta.setNot(new Schema<>()._const(0));
        delta.setDescription("Signed exact stock delta; nonzero, positive for RECEIPT. ADJUSTMENT may be negative within stock invariants.");
        property(schemas, "InventoryAdjustmentRequest", "delta").setNot(new Schema<>()._const(0));
        property(schemas, "InventoryMovement", "reason").setPattern("^[A-Z][A-Z0-9_]{0,63}$");
        property(schemas, "InventoryMovement", "occurredAt").setDescription("UTC occurrence instant, persisted at microsecond precision and retained on replay.");
        property(schemas, "CreateOrderRequest", "items").setMaxItems(maxOrderItems);
        schemas.values().forEach(schema -> {
            if (schema.getProperties() != null) schema.getProperties().values().forEach(valueSchema -> {
                var field = (Schema) valueSchema;
                var pattern = field.getPattern();
                // Some regex engines allow $ before a final newline; Java matches() consumes the full value.
                if (pattern != null && pattern.startsWith("^") && pattern.endsWith("$")) {
                    field.setPattern(pattern.substring(0, pattern.length() - 1) + "(?![\\s\\S])");
                }
            });
        });
    }

    /** Describes owner normalization with code-point atoms valid in Java and ECMAScript with or without its Unicode flag. */
    private static void normalizedText(Schema schema, int maximum, String whitespace, boolean rejectControls, boolean rawBound) {
        schema.setPattern(textPattern(maximum, whitespace, rejectControls, true));
        schema.setMinLength(1);
        schema.setMaxLength(rawBound ? maximum : null);
    }

    /** Keeps non-normalizing commercial identifiers/attribute values nonblank, control-free and unpadded. */
    private static void strictText(Schema schema, int maximum) {
        schema.setPattern(textPattern(maximum, CATALOG_WHITESPACE, true, false));
        schema.setMinLength(1);
        schema.setMaxLength(maximum);
    }

    /** Uses an explicit surrogate-pair alternative without requiring a regex Unicode flag unavailable in OpenAPI. */
    private static String textPattern(int maximum, String whitespace, boolean rejectControls, boolean allowPadding) {
        var edge = "(?:[\\uD800-\\uDBFF][\\uDC00-\\uDFFF]|[^\\uD800-\\uDFFF" + whitespace + "])";
        var controls = rejectControls ? "(?![\\s\\S]*[\\u0000-\\u001F\\u007F-\\u009F])" : "";
        var padding = allowPadding ? "[" + whitespace + "]*" : "";
        return "^" + controls + padding + edge + "(?:" + CODE_POINT
                + "{0," + (maximum - 2) + "}" + edge + ")?" + padding + "$";
    }

    /** Closes actual request records recursively because the configured JSON parser rejects unknown fields. */
    private static void closeRequest(Schema<?> schema, Map<String, Schema> schemas, Set<String> visited) {
        if (schema == null) return;
        if (schema.get$ref() != null) {
            var reference = schema.get$ref();
            if (reference.startsWith("#/components/schemas/") && visited.add(reference)) {
                closeRequest(schemas.get(reference.substring("#/components/schemas/".length())), schemas, visited);
            }
            return;
        }
        if (schema.getProperties() != null) {
            schema.setAdditionalProperties(false);
            schema.getProperties().values().forEach(child -> closeRequest(child, schemas, visited));
        }
        closeRequest(schema.getItems(), schemas, visited);
    }

    /** Required response fields come from the existing reflected records, never a second field inventory. */
    private static void requireAll(Schema<?> schema) {
        if (schema == null || schema.getProperties() == null) throw new IllegalStateException("Expected wire record schema is absent");
        schema.setRequired(new ArrayList<>(schema.getProperties().keySet()));
    }

    /** Fails generation on source drift instead of silently manufacturing an absent wire field. */
    private static Schema property(Map<String, Schema> schemas, String name, String field) {
        var schema = schemas.get(name);
        if (schema == null || schema.getProperties() == null || !schema.getProperties().containsKey(field)) {
            throw new IllegalStateException("Expected wire schema property is absent: " + name + "." + field);
        }
        return (Schema) schema.getProperties().get(field);
    }
}

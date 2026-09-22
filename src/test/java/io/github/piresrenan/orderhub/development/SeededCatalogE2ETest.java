package io.github.piresrenan.orderhub.development;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.StreamSupport;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

/**
 * Why: Catalog administration is the widest v1 HTTP surface and the most exposed to Tenant leakage.
 * Covers: persona access, list/read isolation under deliberately overlapping slugs/SKUs, category hierarchy with
 * reparent and cycle rejection, Product/Variant state machines including terminal ARCHIVED, revisions and pricing.
 * Prevents: cross-Tenant reads or writes, invented transitions and lost-update races on metadata or prices.
 */
class SeededCatalogE2ETest {
    private static SeededE2E e2e;

    @BeforeAll static void start() throws Exception { e2e = SeededE2E.start(); }

    @AfterAll static void stop() { if (e2e != null) e2e.close(); }

    @Test
    void readsAreTenantIsolatedDespiteIdenticalBusinessIdentifiers() throws Exception {
        var alpha = ids(e2e.call("GET", "/catalog/products", "staff", "alpha", null, 200));
        var beta = ids(e2e.call("GET", "/catalog/products", "beta-admin", "beta", null, 200));
        assertThat(alpha).contains(e2e.product("alpha", "paper-ream")).doesNotContain(e2e.product("beta", "paper-ream"));
        assertThat(beta).contains(e2e.product("beta", "paper-ream")).doesNotContainAnyElementsOf(alpha);
        assertThat(ids(e2e.call("GET", "/catalog/categories", "staff", "alpha", null, 200)))
                .contains(e2e.category("alpha", "office")).doesNotContain(e2e.category("beta", "office"));
        e2e.call("GET", "/catalog/products/" + e2e.product("beta", "paper-ream"), "staff", "alpha", null, 404);
        e2e.call("GET", "/catalog/variants/" + e2e.variant("alpha", "PAPER-A4"), "beta-admin", "beta", null, 404);
        e2e.call("GET", "/catalog/categories/" + e2e.category("beta", "office"), "staff", "alpha", null, 404);
        e2e.call("GET", "/catalog/products/" + e2e.product("alpha", "paper-ream"), "staff", "beta", null, 403);

        var variants = e2e.call("GET", "/catalog/products/" + e2e.product("alpha", "paper-ream") + "/variants", "staff", "alpha", null, 200);
        assertThat(StreamSupport.stream(variants.spliterator(), false).map(node -> node.get("sku").asString()).toList())
                .contains("PAPER-A4", "PAPER-LETTER", "PAPER-A3", "PAPER-A5");
        var a4 = e2e.call("GET", "/catalog/variants/" + e2e.variant("alpha", "PAPER-A4"), "staff", "alpha", null, 200);
        assertThat(a4.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(e2e.call("GET", "/catalog/variants/" + e2e.variant("alpha", "PAPER-A3"), "staff", "alpha", null, 200)
                .get("status").asString()).isEqualTo("INACTIVE");
        assertThat(e2e.call("GET", "/catalog/products/" + e2e.product("alpha", "desk-lamp"), "staff", "alpha", null, 200)
                .get("status").asString()).isEqualTo("ARCHIVED");
        assertThat(e2e.call("GET", "/catalog/variants/" + e2e.variant("alpha", "PAPER-A4") + "/prices/USD", "staff", "alpha", null, 200)
                .get("minorUnits").asLong()).isEqualTo(590);
        e2e.call("GET", "/catalog/variants/" + e2e.variant("alpha", "PAPER-A5") + "/prices/BRL", "staff", "alpha", null, 404);

        for (var persona : new String[]{"customer", "alpha-member-no-role", "alpha-suspended", "alpha-terminated", "outsider", "org-viewer"}) {
            e2e.call("GET", "/catalog/products", persona, "alpha", null, 403);
        }
        e2e.call("GET", "/catalog/products", "unbound", "alpha", null, 401);
        e2e.call("GET", "/catalog/products", "multi-tenant-staff", "alpha", null, 200);
    }

    @Test
    void categoryHierarchyRejectsDuplicatesStaleRevisionsAndCycles() throws Exception {
        var root = UUID.randomUUID();
        var child = UUID.randomUUID();
        e2e.call("POST", "/catalog/categories", "staff", "alpha", Map.of("id", root, "name", "E2E root", "slug", "e2e-root"), 201);
        var created = e2e.call("POST", "/catalog/categories", "staff", "alpha",
                Map.of("id", child, "name", "E2E child", "slug", "e2e-child", "parentCategoryId", root), 201);
        e2e.call("POST", "/catalog/categories", "staff", "alpha", Map.of("id", UUID.randomUUID(), "name", "Duplicate", "slug", "office"), 409);
        e2e.call("POST", "/catalog/categories", "beta-admin", "beta", Map.of("id", UUID.randomUUID(), "name", "Beta root", "slug", "e2e-root"), 201);
        e2e.call("POST", "/catalog/categories", "customer", "alpha", Map.of("id", UUID.randomUUID(), "name", "Denied", "slug", "denied"), 403);
        var revision = created.get("revision").asLong();
        var updated = e2e.call("PUT", "/catalog/categories/" + child + "/metadata", "staff", "alpha",
                Map.of("expectedRevision", revision, "name", "E2E child renamed", "slug", "e2e-child"), 200);
        e2e.call("PUT", "/catalog/categories/" + child + "/metadata", "staff", "alpha",
                Map.of("expectedRevision", revision, "name", "Stale", "slug", "e2e-child"), 409);
        var moved = e2e.call("PUT", "/catalog/categories/" + child + "/parent", "staff", "alpha",
                Map.of("expectedRevision", updated.get("revision").asLong(), "parentCategoryId", e2e.category("alpha", "electronics")), 200);
        assertThat(moved.get("parentCategoryId").asString()).isEqualTo(e2e.category("alpha", "electronics"));
        var office = e2e.call("GET", "/catalog/categories/" + e2e.category("alpha", "office"), "staff", "alpha", null, 200);
        e2e.call("PUT", "/catalog/categories/" + e2e.category("alpha", "office") + "/parent", "staff", "alpha",
                Map.of("expectedRevision", office.get("revision").asLong(), "parentCategoryId", e2e.category("alpha", "office-paper-premium")), 409);
        // A foreign Tenant's category is indistinguishable from one that never existed (no enumeration).
        e2e.call("PUT", "/catalog/categories/" + child + "/parent", "staff", "alpha",
                Map.of("expectedRevision", moved.get("revision").asLong(), "parentCategoryId", e2e.category("beta", "office")), 409);
        e2e.call("PUT", "/catalog/categories/" + child + "/parent", "staff", "alpha",
                Map.of("expectedRevision", moved.get("revision").asLong(), "parentCategoryId", UUID.randomUUID()), 409);
        assertThat(e2e.call("GET", "/catalog/categories/" + child, "staff", "alpha", null, 200).get("parentCategoryId").asString())
                .isEqualTo(e2e.category("alpha", "electronics"));
        var root2 = e2e.call("GET", "/catalog/categories/" + child, "staff", "alpha", null, 200);
        e2e.call("PUT", "/catalog/categories/" + child + "/parent", "staff", "alpha",
                Map.of("expectedRevision", root2.get("revision").asLong()), 200);
    }

    @Test
    void productAndVariantStateMachinesEnforceTransitionsAndTerminalArchive() throws Exception {
        var product = UUID.randomUUID();
        var variant = UUID.randomUUID();
        var created = e2e.call("POST", "/catalog/products", "staff", "alpha",
                Map.of("id", product, "name", "E2E product", "slug", "e2e-product", "brand", "Synthetic"), 201);
        assertThat(created.get("status").asString()).isEqualTo("DRAFT");
        e2e.call("POST", "/catalog/products", "staff", "alpha", Map.of("id", UUID.randomUUID(), "name", "Dup", "slug", "paper-ream"), 409);
        var draft = e2e.call("POST", "/catalog/products/" + product + "/variants", "staff", "alpha",
                Map.of("id", variant, "sku", "E2E-SKU", "attributes", List.of(Map.of("key", "size", "value", "M"))), 201);
        e2e.call("POST", "/catalog/products/" + product + "/variants", "staff", "alpha",
                Map.of("id", UUID.randomUUID(), "sku", "PAPER-A4", "attributes", List.of()), 409);
        e2e.call("POST", "/catalog/variants/" + variant + "/deactivate", "staff", "alpha",
                Map.of("expectedRevision", draft.get("revision").asLong()), 409);
        var active = e2e.call("POST", "/catalog/variants/" + variant + "/activate", "staff", "alpha",
                Map.of("expectedRevision", draft.get("revision").asLong()), 200);
        e2e.call("POST", "/catalog/variants/" + variant + "/activate", "staff", "alpha",
                Map.of("expectedRevision", draft.get("revision").asLong()), 409);
        var product2 = e2e.call("POST", "/catalog/products/" + product + "/activate", "staff", "alpha",
                Map.of("expectedRevision", created.get("revision").asLong()), 200);
        assertThat(product2.get("status").asString()).isEqualTo("ACTIVE");
        var inactive = e2e.call("POST", "/catalog/variants/" + variant + "/deactivate", "staff", "alpha",
                Map.of("expectedRevision", active.get("revision").asLong()), 200);
        assertThat(inactive.get("status").asString()).isEqualTo("INACTIVE");
        var reactivated = e2e.call("POST", "/catalog/variants/" + variant + "/activate", "staff", "alpha",
                Map.of("expectedRevision", inactive.get("revision").asLong()), 200);
        var updated = e2e.call("PUT", "/catalog/variants/" + variant + "/metadata", "staff", "alpha",
                Map.of("expectedRevision", reactivated.get("revision").asLong(), "sku", "E2E-SKU", "displayName", "Medium",
                        "attributes", List.of(Map.of("key", "size", "value", "M"))), 200);
        var archived = e2e.call("POST", "/catalog/variants/" + variant + "/archive", "staff", "alpha",
                Map.of("expectedRevision", updated.get("revision").asLong()), 200);
        assertThat(archived.get("status").asString()).isEqualTo("ARCHIVED");
        e2e.call("POST", "/catalog/variants/" + variant + "/activate", "staff", "alpha",
                Map.of("expectedRevision", archived.get("revision").asLong()), 409);
        var metadata = e2e.call("PUT", "/catalog/products/" + product + "/metadata", "staff", "alpha",
                Map.of("expectedRevision", product2.get("revision").asLong(), "name", "E2E product renamed", "slug", "e2e-product"), 200);
        var assigned = e2e.call("PUT", "/catalog/products/" + product + "/categories", "staff", "alpha",
                Map.of("expectedRevision", metadata.get("revision").asLong(), "categoryIds", List.of(e2e.category("alpha", "office"))), 200);
        e2e.call("PUT", "/catalog/products/" + product + "/categories", "staff", "alpha",
                Map.of("expectedRevision", assigned.get("revision").asLong(), "categoryIds", List.of(e2e.category("beta", "office"))), 404);
        var productArchived = e2e.call("POST", "/catalog/products/" + product + "/archive", "staff", "alpha",
                Map.of("expectedRevision", assigned.get("revision").asLong()), 200);
        assertThat(productArchived.get("status").asString()).isEqualTo("ARCHIVED");
        e2e.call("POST", "/catalog/products/" + product + "/activate", "staff", "alpha",
                Map.of("expectedRevision", productArchived.get("revision").asLong()), 409);
        e2e.call("POST", "/catalog/products/" + product + "/archive", "beta-admin", "beta",
                Map.of("expectedRevision", productArchived.get("revision").asLong()), 404);
        // The same SKU is legal in another Tenant.
        e2e.call("POST", "/catalog/products/" + e2e.product("beta", "standing-desk") + "/variants", "beta-admin", "beta",
                Map.of("id", UUID.randomUUID(), "sku", "E2E-SKU", "attributes", List.of()), 201);
    }

    @Test
    void pricesAreRevisionedAndRequirePriceAuthority() throws Exception {
        var path = "/catalog/variants/" + e2e.variant("alpha", "PAPER-LETTER") + "/prices/EUR";
        e2e.call("GET", path, "staff", "alpha", null, 404);
        var set = e2e.call("PUT", path, "staff", "alpha", Map.of("expectedRevision", 0, "minorUnits", 499), 200);
        e2e.call("PUT", path, "staff", "alpha", Map.of("expectedRevision", 0, "minorUnits", 599), 409);
        e2e.call("PUT", path, "staff", "alpha", Map.of("expectedRevision", set.get("revision").asLong(), "minorUnits", 599), 200);
        assertThat(e2e.call("GET", path, "staff", "alpha", null, 200).get("minorUnits").asLong()).isEqualTo(599);
        e2e.call("PUT", path, "customer", "alpha", Map.of("expectedRevision", 0, "minorUnits", 1), 403);
        e2e.call("GET", "/catalog/variants/" + e2e.variant("alpha", "PAPER-LETTER") + "/prices/EUR", "beta-admin", "beta", null, 404);
        e2e.call("PUT", path.replace("EUR", "XYZ"), "staff", "alpha", Map.of("expectedRevision", 0, "minorUnits", 1), 400);
    }

    private static List<String> ids(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false).map(node -> node.get("id").asString()).toList();
    }
}

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
 * Why: stock and Customer Orders carry the v1 durable business effects.
 * Covers: inventory reads, idempotent movements, policy/safety-stock revisions, DENY versus ALLOW_BACKORDER
 * allocation, multi-item Orders, idempotent replay and fingerprint conflict, Customer ownership and Tenant isolation.
 * Prevents: stock changes in the wrong Tenant, duplicate reservations and Orders visible to other Customers.
 */
class SeededInventoryOrderE2ETest {
    private static SeededE2E e2e;

    @BeforeAll static void start() throws Exception { e2e = SeededE2E.start(); }

    @AfterAll static void stop() { if (e2e != null) e2e.close(); }

    @Test
    void inventoryReadsReflectTheSeedAndAreTenantIsolated() throws Exception {
        var positions = e2e.call("GET", "/inventory/positions", "staff", "alpha", null, 200);
        var variants = StreamSupport.stream(positions.spliterator(), false).map(node -> node.get("variantId").asString()).toList();
        assertThat(variants).contains(e2e.variant("alpha", "PAPER-A4"), e2e.variant("alpha", "PEN-BLUE"))
                .doesNotContain(e2e.variant("beta", "PAPER-A4"));
        var a4 = e2e.call("GET", "/inventory/positions/" + e2e.variant("alpha", "PAPER-A4"), "staff", "alpha", null, 200);
        assertThat(List.of(a4.get("onHand").asLong(), a4.get("committed").asLong(), a4.get("safetyStock").asLong())).containsExactly(48L, 3L, 5L);
        var backordered = e2e.call("GET", "/inventory/positions/" + e2e.variant("beta", "PAPER-A4"), "beta-admin", "beta", null, 200);
        assertThat(backordered.get("backordered").asLong()).isEqualTo(3);
        var movements = e2e.call("GET", "/inventory/positions/" + e2e.variant("alpha", "PAPER-A4") + "/movements", "staff", "alpha", null, 200);
        assertThat(StreamSupport.stream(movements.spliterator(), false).map(node -> node.get("type").asString()).toList())
                .contains("RECEIPT", "ADJUSTMENT");
        e2e.call("GET", "/inventory/positions/" + e2e.variant("alpha", "PAPER-A4"), "beta-admin", "beta", null, 404);
        e2e.call("GET", "/inventory/positions/" + e2e.variant("alpha", "PAPER-A4"), "staff", "beta", null, 403);
        for (var persona : new String[]{"customer", "alpha-member-no-role", "alpha-suspended", "org-viewer"}) {
            e2e.call("GET", "/inventory/positions", persona, "alpha", null, 403);
        }
        assertThat(e2e.call("GET", "/inventory/policy", "staff", "alpha", null, 200).get("policy").asString()).isEqualTo("DENY");
        assertThat(e2e.call("GET", "/inventory/policy", "beta-admin", "beta", null, 200).get("policy").asString()).isEqualTo("ALLOW_BACKORDER");
    }

    @Test
    void movementsAreIdempotentValidatedAndConfinedToTheirTenant() throws Exception {
        var variant = e2e.variant("alpha", "PAPER-LETTER");
        var receipt = Map.of("operationId", UUID.randomUUID(), "variantId", variant, "quantity", 4, "reason", "E2E_RECEIPT");
        var first = e2e.call("POST", "/inventory/receipts", "staff", "alpha", receipt, 201);
        assertThat(e2e.call("POST", "/inventory/receipts", "staff", "alpha", receipt, 201)).isEqualTo(first);
        var changed = new java.util.HashMap<String, Object>(receipt);
        changed.put("quantity", 5);
        e2e.call("POST", "/inventory/receipts", "staff", "alpha", changed, 409);
        e2e.call("POST", "/inventory/adjustments", "staff", "alpha",
                Map.of("operationId", UUID.randomUUID(), "variantId", variant, "delta", -1000, "reason", "E2E_TOO_LOW"), 409);
        e2e.call("POST", "/inventory/adjustments", "staff", "alpha",
                Map.of("operationId", UUID.randomUUID(), "variantId", variant, "delta", -1, "reason", "E2E_COUNT"), 201);
        e2e.call("POST", "/inventory/receipts", "staff", "alpha",
                Map.of("operationId", UUID.randomUUID(), "variantId", e2e.variant("beta", "PAPER-LETTER"), "quantity", 1, "reason", "E2E_FOREIGN"), 404);
        e2e.call("POST", "/inventory/receipts", "customer", "alpha",
                Map.of("operationId", UUID.randomUUID(), "variantId", variant, "quantity", 1, "reason", "E2E_DENIED"), 403);
        assertThat(e2e.call("GET", "/inventory/positions/" + variant, "staff", "alpha", null, 200).get("onHand").asLong()).isEqualTo(23);
        assertThat(e2e.call("GET", "/inventory/positions/" + e2e.variant("beta", "PAPER-LETTER"), "beta-admin", "beta", null, 200)
                .get("onHand").asLong()).isEqualTo(20);
    }

    @Test
    void policyAndSafetyStockUseOptimisticExpectations() throws Exception {
        // Requesting the value already in force is an idempotent success; a stale expectation for a change conflicts.
        e2e.call("PUT", "/inventory/policy", "multi-tenant-staff", "gamma",
                Map.of("expectedPolicy", "ALLOW_BACKORDER", "policy", "DENY", "reason", "E2E_ALREADY"), 200);
        e2e.call("PUT", "/inventory/policy", "multi-tenant-staff", "gamma",
                Map.of("expectedPolicy", "ALLOW_BACKORDER", "policy", "ALLOW_BACKORDER", "reason", "E2E_STALE"), 409);
        e2e.call("PUT", "/inventory/policy", "multi-tenant-staff", "gamma",
                Map.of("expectedPolicy", "DENY", "policy", "ALLOW_BACKORDER", "reason", "E2E_POLICY"), 200);
        assertThat(e2e.call("GET", "/inventory/policy", "multi-tenant-staff", "gamma", null, 200).get("policy").asString())
                .isEqualTo("ALLOW_BACKORDER");
        assertThat(e2e.call("GET", "/inventory/policy", "staff", "alpha", null, 200).get("policy").asString()).isEqualTo("DENY");
        var safety = "/inventory/positions/" + e2e.variant("gamma", "PAPER-A4") + "/safety-stock";
        e2e.call("PUT", safety, "multi-tenant-staff", "gamma", Map.of("expectedSafetyStock", 0, "safetyStock", 3, "reason", "E2E_SAFETY"), 200);
        e2e.call("PUT", safety, "multi-tenant-staff", "gamma", Map.of("expectedSafetyStock", 0, "safetyStock", 4, "reason", "E2E_SAFETY"), 409);
        e2e.call("PUT", safety, "customer", "gamma", Map.of("expectedSafetyStock", 3, "safetyStock", 4, "reason", "E2E_SAFETY"), 403);
        e2e.call("PUT", "/inventory/policy", "customer", "alpha", Map.of("policy", "DENY", "reason", "E2E"), 403);
    }

    @Test
    void ordersAllocateByTenantPolicyAndReplayIdempotently() throws Exception {
        var pen = Map.of("customerId", e2e.customer("alpha-linked"), "items", List.of(Map.of("variantId", e2e.variant("alpha", "PEN-BLUE"), "quantity", 1)));
        e2e.order("customer", "alpha", "e2e-" + UUID.randomUUID(), pen, 409);
        var backorder = e2e.order("beta-customer", "beta", "e2e-" + UUID.randomUUID(), Map.of("customerId", e2e.customer("beta-linked"),
                "items", List.of(Map.of("variantId", e2e.variant("beta", "PEN-BLUE"), "quantity", 1))), 201);
        assertThat(backorder.get("allocationOutcome").asString()).isEqualTo("FULLY_BACKORDERED");

        var body = Map.of("customerId", e2e.customer("alpha-second"), "items", List.of(
                Map.of("variantId", e2e.variant("alpha", "PAPER-A4"), "quantity", 1),
                Map.of("variantId", e2e.variant("alpha", "PAPER-LETTER"), "quantity", 1)));
        var key = "e2e-" + UUID.randomUUID();
        var created = e2e.order("alpha-customer-2", "alpha", key, body, 201);
        assertThat(created.get("items").size()).isEqualTo(2);
        assertThat(created.get("allocationOutcome").asString()).isEqualTo("FULLY_ALLOCATED");
        assertThat(e2e.order("alpha-customer-2", "alpha", key, body, 201)).isEqualTo(created);
        var changed = Map.of("customerId", e2e.customer("alpha-second"), "items", List.of(
                Map.of("variantId", e2e.variant("alpha", "PAPER-A4"), "quantity", 2)));
        e2e.order("alpha-customer-2", "alpha", key, changed, 422);
        assertThat(e2e.call("GET", "/inventory/positions/" + e2e.variant("alpha", "PAPER-A4"), "staff", "alpha", null, 200)
                .get("committed").asLong()).isEqualTo(4);

        e2e.order("staff", "alpha", "e2e-" + UUID.randomUUID(), body, 403);
        e2e.order("customer", "alpha", "e2e-" + UUID.randomUUID(), body, 403);
        e2e.order("alpha-customer-2", "beta", "e2e-" + UUID.randomUUID(), body, 403);
        e2e.order("customer", "alpha", "e2e-" + UUID.randomUUID(), Map.of("customerId", e2e.customer("alpha-linked"),
                "items", List.of(Map.of("variantId", e2e.variant("beta", "PAPER-A4"), "quantity", 1))), 409);

        var seeded = e2e.seededOrder("alpha-single-item");
        var replay = e2e.order("customer", "alpha", seeded.get("idempotencyKey").asString(), Map.of("customerId", e2e.customer("alpha-linked"),
                "items", List.of(Map.of("variantId", e2e.variant("alpha", "PAPER-A4"), "quantity", 2))), 201);
        assertThat(replay.get("id").asString()).isEqualTo(seeded.get("orderId").asString());
        assertThat(e2e.jdbc.queryForObject("SELECT count(*) FROM orders.orders WHERE tenant_id = ?", Integer.class,
                UUID.fromString(e2e.tenant("alpha")))).isEqualTo(3);
    }

    @Test
    void ordersAreVisibleOnlyToTheirCustomerInTheirTenant() throws Exception {
        var single = e2e.seededOrder("alpha-single-item").get("orderId").asString();
        var shared = e2e.seededOrder("beta-full-backorder").get("orderId").asString();
        JsonNode own = e2e.call("GET", "/orders/" + single, "customer", "alpha", null, 200);
        assertThat(own.get("customerId").asString()).isEqualTo(e2e.customer("alpha-linked"));
        e2e.call("GET", "/orders/" + single, "alpha-customer-2", "alpha", null, 404);
        e2e.call("GET", "/orders/" + single, "customer", "beta", null, 404);
        e2e.call("GET", "/orders/" + shared, "customer", "beta", null, 200);
        e2e.call("GET", "/orders/" + shared, "beta-customer", "beta", null, 404);
        e2e.call("GET", "/orders/" + single, "beta-customer", "alpha", null, 403);
        // Anything that is not the caller's own Order is indistinguishable from an absent Order (404);
        // a caller without a membership in the selected Tenant is refused at the Tenant boundary (403).
        e2e.call("GET", "/orders/" + single, "staff", "alpha", null, 404);
        e2e.call("GET", "/orders/" + UUID.randomUUID(), "staff", "alpha", null, 404);
        e2e.call("GET", "/orders/" + single, "unbound", "alpha", null, 401);
        e2e.call("GET", "/orders/" + UUID.randomUUID(), "customer", "alpha", null, 404);
    }
}

package io.github.piresrenan.orderhub.development;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

/**
 * Why: every seeded persona must discover exactly its currently selectable Tenants, and nothing else, over a real
 * socket (ADR-0021).
 * Covers: GET /tenants through HTTP, JWT verification against the loopback issuer, internal identity, the Users
 * membership scan, the Tenants ACTIVE batch and PostgreSQL, for every persona in the seed manifest.
 * Expected: ACTIVE memberships in ACTIVE Tenants only, as {id,name} in identifier order with no-store; suspended or
 * terminated memberships, Platform-only and outsider identities see an empty final page; unbound and anonymous
 * callers get 401; limit=1 walks the set exactly once through nextAfterId.
 * Prevents: discovery drifting from the manifest's authority model, Platform grants implying Tenant membership and
 * cursor walks that skip or repeat Tenants.
 */
class SeededTenantDiscoveryE2ETest {
    private static final Set<String> MEMBERSHIPS = Set.of("STAFF_GOVERNANCE", "STAFF_NO_ROLE", "CUSTOMER");
    private static SeededE2E e2e;

    @BeforeAll static void start() throws Exception { e2e = SeededE2E.start(); }

    @AfterAll static void stop() { if (e2e != null) e2e.close(); }

    @Test
    void everyPersonaDiscoversExactlyItsSelectableTenants() throws Exception {
        var checked = 0;
        for (var persona : e2e.manifest.get("personas")) {
            var name = persona.get("name").asString();
            if ("unbound".equals(name)) continue;
            var expected = expected(persona);
            var response = e2e.raw("GET", "/tenants", e2e.token(name), null, null, null);
            assertThat(response.headers().firstValue("Cache-Control")).as(name).contains("no-store");
            var page = e2e.read(response, 200);
            assertThat(page.get("nextAfterId").isNull()).as(name).isTrue();
            var ids = new ArrayList<String>();
            for (var item : page.get("items")) {
                assertThat(item.propertyNames()).as(name).containsExactlyInAnyOrder("id", "name");
                var tenant = SeededE2E.find(e2e.manifest.get("tenants"), "id", item.get("id").asString());
                assertThat(item.get("name").asString()).isEqualTo(tenant.get("name").asString());
                ids.add(item.get("id").asString());
            }
            assertThat(ids).as(name).containsExactlyElementsOf(expected);
            checked++;
        }
        assertThat(checked).isGreaterThanOrEqualTo(10);
        assertThat(expected(SeededE2E.find(e2e.manifest.get("personas"), "name", "multi-tenant-staff"))).hasSize(2);
        assertThat(expected(SeededE2E.find(e2e.manifest.get("personas"), "name", "platform"))).isEmpty();

        e2e.call("GET", "/tenants", "unbound", null, null, 401);
        e2e.read(e2e.raw("GET", "/tenants", null, null, null, null), 401);

        // limit=1 walk: filtered memberships yield empty pages that still advance; only a null cursor ends the scan.
        for (var persona : List.of("multi-tenant-staff", "customer")) {
            var walked = new ArrayList<String>(); String cursor = null; var pages = 0;
            do {
                var page = e2e.call("GET", "/tenants?limit=1" + (cursor == null ? "" : "&afterId=" + cursor), persona, null, null, 200);
                assertThat(page.get("items").size()).isLessThanOrEqualTo(1);
                page.get("items").forEach(item -> walked.add(item.get("id").asString()));
                cursor = page.get("nextAfterId").isNull() ? null : page.get("nextAfterId").asString();
                assertThat(++pages).as("bounded walk").isLessThan(20);
            } while (cursor != null);
            assertThat(walked).as(persona).containsExactlyElementsOf(expected(SeededE2E.find(e2e.manifest.get("personas"), "name", persona)));
        }
        e2e.call("GET", "/tenants?limit=101", "multi-tenant-staff", null, null, 400);
    }

    /** Selectable = an active membership (with or without a role) in a Tenant whose status is ACTIVE. */
    private static List<String> expected(JsonNode persona) {
        var result = new TreeSet<String>();
        var access = persona.get("tenantAccess");
        for (var key : access.propertyNames()) {
            var tenant = SeededE2E.find(e2e.manifest.get("tenants"), "key", key);
            if (MEMBERSHIPS.contains(access.get(key).asString()) && "ACTIVE".equals(tenant.get("status").asString())) {
                result.add(tenant.get("id").asString());
            }
        }
        return List.copyOf(result);
    }
}

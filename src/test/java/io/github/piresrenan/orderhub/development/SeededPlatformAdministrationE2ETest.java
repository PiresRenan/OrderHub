package io.github.piresrenan.orderhub.development;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;
import java.util.stream.StreamSupport;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

/**
 * Why: Platform and Organization administration are v1 public capabilities with no other socket-level proof.
 * Covers: authentication/authorization matrix, Organization and Tenant lifecycle with repeat/unknown targets,
 * placement attach/move/detach including conflicts, organization grants and anti-enumerating tenant listing.
 * Prevents: Platform authority leaking to Staff/Organization personas or Tenant business routes.
 */
class SeededPlatformAdministrationE2ETest {
    private static SeededE2E e2e;

    @BeforeAll static void start() throws Exception { e2e = SeededE2E.start(); }

    @AfterAll static void stop() { if (e2e != null) e2e.close(); }

    @Test
    void platformRoutesRequirePlatformAuthorityAndPlatformHasNoTenantBusinessAccess() throws Exception {
        e2e.read(e2e.raw("GET", "/platform/organizations", null, null, null, null), 401);
        e2e.read(e2e.raw("GET", "/platform/organizations", "not-a-jwt", null, null, null), 401);
        e2e.call("GET", "/platform/organizations", "unbound", null, null, 401);
        for (var persona : new String[]{"outsider", "staff", "org-viewer", "customer"}) {
            e2e.call("GET", "/platform/organizations", persona, null, null, 403);
            e2e.call("POST", "/platform/tenants", persona, null, Map.of("name", "Denied tenant"), 403);
        }
        var listed = e2e.call("GET", "/platform/organizations", "platform", null, null, 200);
        for (var key : new String[]{"north", "south", "dormant"}) {
            assertThat(ids(listed)).contains(e2e.organization(key));
        }
        e2e.call("GET", "/catalog/products", "platform", "alpha", null, 403);
        e2e.call("GET", "/inventory/positions", "platform", "beta", null, 403);
    }

    @Test
    void organizationSuspensionIsIdempotentAndVisible() throws Exception {
        var created = e2e.call("POST", "/platform/organizations", "platform", null, Map.of("name", "E2E lifecycle organization"), 201);
        var id = created.get("id").asString();
        assertThat(created.get("status").asString()).isEqualTo("ACTIVE");
        e2e.call("POST", "/platform/organizations", "platform", null, Map.of("name", "   "), 400);
        e2e.call("PUT", "/platform/organizations/" + id + "/suspension", "platform", null, null, 204);
        e2e.call("PUT", "/platform/organizations/" + id + "/suspension", "platform", null, null, 204);
        assertThat(status(e2e.call("GET", "/platform/organizations", "platform", null, null, 200), id)).isEqualTo("SUSPENDED");
        e2e.call("DELETE", "/platform/organizations/" + id + "/suspension", "platform", null, null, 204);
        e2e.call("DELETE", "/platform/organizations/" + id + "/suspension", "platform", null, null, 204);
        assertThat(status(e2e.call("GET", "/platform/organizations", "platform", null, null, 200), id)).isEqualTo("ACTIVE");
        e2e.call("PUT", "/platform/organizations/" + UUID.randomUUID() + "/suspension", "platform", null, null, 404);
        e2e.call("PUT", "/platform/organizations/" + id + "/suspension", "staff", null, null, 403);
        assertThat(e2e.jdbc.queryForObject("SELECT count(*) FROM organizations.administrative_audit_events WHERE organization_id = ?",
                Integer.class, UUID.fromString(id))).isGreaterThanOrEqualTo(3);
    }

    @Test
    void tenantSuspensionClosesAndRecoveryReopensOnlyThatTenant() throws Exception {
        e2e.call("GET", "/catalog/products", "multi-tenant-staff", "gamma", null, 200);
        e2e.call("PUT", "/platform/tenants/" + e2e.tenant("gamma") + "/suspension", "platform", null, null, 204);
        e2e.call("PUT", "/platform/tenants/" + e2e.tenant("gamma") + "/suspension", "platform", null, null, 204);
        e2e.call("GET", "/catalog/products", "multi-tenant-staff", "gamma", null, 403);
        e2e.call("GET", "/catalog/products", "multi-tenant-staff", "alpha", null, 200);
        e2e.call("DELETE", "/platform/tenants/" + e2e.tenant("gamma") + "/suspension", "platform", null, null, 204);
        e2e.call("GET", "/catalog/products", "multi-tenant-staff", "gamma", null, 200);
        e2e.call("PUT", "/platform/tenants/" + UUID.randomUUID() + "/suspension", "platform", null, null, 404);
        e2e.call("PUT", "/platform/tenants/" + e2e.tenant("gamma") + "/suspension", "multi-tenant-staff", null, null, 403);
        // The seeded suspended Tenant stays closed to every persona.
        e2e.call("GET", "/catalog/products", "staff", "delta", null, 403);
    }

    @Test
    void placementsAttachMoveAndDetachWithConflicts() throws Exception {
        var tenant = e2e.call("POST", "/platform/tenants", "platform", null, Map.of("name", "E2E placement tenant"), 201).get("id").asString();
        var north = e2e.organization("north");
        var south = e2e.organization("south");
        e2e.call("PUT", "/platform/organizations/" + north + "/tenants/" + tenant, "platform", null, null, 204);
        e2e.call("PUT", "/platform/organizations/" + north + "/tenants/" + tenant, "platform", null, null, 204);
        e2e.call("PUT", "/platform/organizations/" + south + "/tenants/" + tenant, "platform", null, null, 409);
        e2e.call("PUT", "/platform/organizations/" + e2e.organization("dormant") + "/tenants/" + UUID.randomUUID(), "platform", null, null, 404);
        e2e.call("POST", "/platform/organizations/" + south + "/tenants/" + tenant + "/moves", "platform", null,
                Map.of("destinationOrganizationId", north), 409);
        e2e.call("POST", "/platform/organizations/" + north + "/tenants/" + tenant + "/moves", "platform", null,
                Map.of("destinationOrganizationId", e2e.organization("dormant")), 409);
        e2e.call("POST", "/platform/organizations/" + north + "/tenants/" + tenant + "/moves", "platform", null,
                Map.of("destinationOrganizationId", south), 204);
        assertThat(placement(tenant)).isEqualTo(UUID.fromString(south));
        e2e.call("DELETE", "/platform/organizations/" + north + "/tenants/" + tenant, "platform", null, null, 409);
        e2e.call("DELETE", "/platform/organizations/" + south + "/tenants/" + tenant, "platform", null, null, 204);
        assertThat(e2e.jdbc.queryForObject("SELECT count(*) FROM organizations.tenant_placements WHERE tenant_id = ?",
                Integer.class, UUID.fromString(tenant))).isZero();
        e2e.call("DELETE", "/platform/organizations/" + south + "/tenants/" + tenant, "platform", null, null, 204);
        e2e.call("PUT", "/platform/organizations/" + north + "/tenants/" + tenant, "staff", null, null, 403);
    }

    @Test
    void organizationGrantsControlAntiEnumeratingTenantListing() throws Exception {
        var listed = e2e.call("GET", "/organizations/" + e2e.organization("north") + "/tenants", "org-viewer", null, null, 200);
        assertThat(ids(listed)).containsExactlyInAnyOrder(e2e.tenant("alpha"), e2e.tenant("beta"));
        var south = "/organizations/" + e2e.organization("south") + "/tenants";
        e2e.call("GET", south, "org-viewer", null, null, 404);
        e2e.call("GET", "/organizations/" + e2e.organization("north") + "/tenants", "outsider", null, null, 404);
        var grant = "/platform/organizations/" + e2e.organization("south") + "/administrative-grants/" + e2e.user("org-viewer")
                + "/permissions/ORGANIZATION_TENANTS_VIEW";
        e2e.call("PUT", grant, "staff", null, null, 403);
        e2e.call("PUT", grant, "platform", null, null, 204);
        e2e.call("PUT", grant, "platform", null, null, 204);
        assertThat(ids(e2e.call("GET", south, "org-viewer", null, null, 200)))
                .containsExactlyInAnyOrder(e2e.tenant("gamma"), e2e.tenant("epsilon"));
        e2e.call("DELETE", grant, "platform", null, null, 204);
        e2e.call("DELETE", grant, "platform", null, null, 204);
        e2e.call("GET", south, "org-viewer", null, null, 404);
        e2e.call("PUT", grant.replace("ORGANIZATION_TENANTS_VIEW", "NOT_A_PERMISSION"), "platform", null, null, 400);
        // Organization visibility never grants Tenant business access.
        e2e.call("GET", "/catalog/products", "org-viewer", "alpha", null, 403);
    }

    private static UUID placement(String tenant) {
        return e2e.jdbc.queryForObject("SELECT organization_id FROM organizations.tenant_placements WHERE tenant_id = ?",
                UUID.class, UUID.fromString(tenant));
    }

    private static java.util.List<String> ids(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false).map(node -> node.get("id").asString()).toList();
    }

    private static String status(JsonNode organizations, String id) {
        return SeededE2E.find(organizations, "id", id).get("status").asString();
    }
}

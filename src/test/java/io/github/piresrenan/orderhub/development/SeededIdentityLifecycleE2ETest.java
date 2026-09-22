package io.github.piresrenan.orderhub.development;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.HashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.MethodOrderer;
import org.junit.jupiter.api.Order;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.TestMethodOrder;

/**
 * Why: Staff provisioning, membership lifecycle and Customer linking establish all v1 Tenant authority.
 * Covers: normal Staff provisioning with the manifest placement (role / no role), replay, cancellation and
 * cancelled-proof rejection; suspend/recover/terminate including repeat, self and terminal behavior; Customer
 * account-link issue/replay/cancel/consume with reuse and wrong-Tenant rejection; the initial-Staff ceremony on the
 * empty Tenant (runs last because it binds the single unbound identity).
 * Prevents: proofs that outlive cancellation, authority from the wrong Tenant and resurrection of terminated members.
 */
@TestMethodOrder(MethodOrderer.OrderAnnotation.class)
class SeededIdentityLifecycleE2ETest {
    private static SeededE2E e2e;

    @BeforeAll static void start() throws Exception { e2e = SeededE2E.start(); }

    @AfterAll static void stop() { if (e2e != null) e2e.close(); }

    @Test @Order(1)
    void normalStaffProvisioningUsesThePublishedPlacementAndHonoursCancellation() throws Exception {
        var path = "/administration/tenants/" + e2e.tenant("alpha") + "/staff-provisioning";
        var request = new HashMap<String, Object>(SeededE2E.operation());
        request.put("departmentId", e2e.placement("alpha").get("departmentId").asString());
        request.put("positionId", e2e.placement("alpha").get("positionId").asString());
        var issued = e2e.call("POST", path, "staff", null, request, 200);
        assertThat(issued.hasNonNull("credential")).isTrue();
        var replay = e2e.call("POST", path, "staff", null, request, 200);
        assertThat(replay.has("credential")).isFalse();
        assertThat(replay.get("intentId")).isEqualTo(issued.get("intentId"));
        var cancel = path + "/" + issued.get("intentId").asString();
        assertThat(e2e.call("DELETE", cancel, "staff", null, null, 200).get("changed").asBoolean()).isTrue();
        assertThat(e2e.call("DELETE", cancel, "staff", null, null, 200).get("changed").asBoolean()).isFalse();
        e2e.call("POST", "/identity/bootstrap/staff", "unbound", null, Map.of("credential", issued.get("credential").asString()), 403);

        for (var persona : new String[]{"alpha-member-no-role", "alpha-suspended", "customer", "platform", "beta-admin"}) {
            e2e.call("POST", path, persona, null, withOperation(request), 403);
        }
        var foreign = withOperation(request);
        foreign.put("departmentId", UUID.randomUUID());
        // Identity lifecycle refusals are one uniform, non-enumerating policy failure.
        e2e.call("POST", path, "staff", null, foreign, 403);
        var outstanding = SeededE2E.find(e2e.manifest.get("outstanding").get("staffProvisioningIntents"), "tenant", "alpha");
        assertThat(e2e.call("DELETE", path + "/" + outstanding.get("intentId").asString(), "staff", null, null, 200)
                .get("changed").asBoolean()).isTrue();
    }

    @Test @Order(2)
    void membershipLifecycleIsIdempotentScopedAndTerminal() throws Exception {
        var base = "/administration/tenants/" + e2e.tenant("alpha") + "/memberships/";
        var target = base + e2e.user("alpha-member-no-role");
        assertThat(e2e.call("POST", target + "/suspend", "staff", null, null, 200).get("changed").asBoolean()).isTrue();
        assertThat(e2e.call("POST", target + "/suspend", "staff", null, null, 200).get("changed").asBoolean()).isFalse();
        assertThat(e2e.call("POST", target + "/recover", "staff", null, null, 200).get("changed").asBoolean()).isTrue();

        var suspended = base + e2e.user("alpha-suspended");
        e2e.call("GET", "/catalog/products", "alpha-suspended", "alpha", null, 403);
        e2e.call("POST", suspended + "/recover", "staff", null, null, 200);
        e2e.call("GET", "/catalog/products", "alpha-suspended", "alpha", null, 200);
        e2e.call("POST", suspended + "/suspend", "multi-tenant-staff", null, null, 200);
        e2e.call("GET", "/catalog/products", "alpha-suspended", "alpha", null, 403);

        e2e.call("POST", base + e2e.user("staff") + "/suspend", "staff", null, null, 403);
        e2e.call("POST", target + "/terminate", "alpha-member-no-role", null, null, 403);
        e2e.call("POST", "/administration/tenants/" + e2e.tenant("beta") + "/memberships/" + e2e.user("alpha-member-no-role")
                + "/suspend", "staff", null, null, 403);
        e2e.call("POST", "/administration/tenants/" + e2e.tenant("beta") + "/memberships/" + e2e.user("alpha-member-no-role")
                + "/suspend", "beta-admin", null, null, 403);

        assertThat(e2e.call("POST", target + "/terminate", "staff", null, null, 200).get("changed").asBoolean()).isTrue();
        // TERMINATED is terminal: recovery is refused (uniform policy failure) and the durable state is unchanged.
        e2e.call("POST", target + "/recover", "staff", null, null, 403);
        e2e.call("POST", base + e2e.user("alpha-terminated") + "/recover", "staff", null, null, 403);
        e2e.call("GET", "/catalog/products", "alpha-terminated", "alpha", null, 403);
        assertThat(e2e.jdbc.queryForObject("SELECT status FROM users.tenant_memberships WHERE tenant_id = ? AND user_id = ?",
                String.class, UUID.fromString(e2e.tenant("alpha")), UUID.fromString(e2e.user("alpha-member-no-role"))))
                .isEqualTo("TERMINATED");
    }

    @Test @Order(3)
    void customerAccountLinkProofsAreReplayableCancellableAndTenantBound() throws Exception {
        var customer = e2e.customer("alpha-unlinked");
        var issuePath = "/administration/tenants/" + e2e.tenant("alpha") + "/customers/" + customer + "/account-link-proofs";
        var request = SeededE2E.operation();
        var issued = e2e.call("POST", issuePath, "staff", null, request, 200);
        assertThat(e2e.call("POST", issuePath, "staff", null, request, 200).has("credential")).isFalse();
        var cancelPath = "/administration/tenants/" + e2e.tenant("alpha") + "/customer-account-link-proofs/" + issued.get("proofId").asString();
        assertThat(e2e.call("DELETE", cancelPath, "staff", null, null, 200).get("changed").asBoolean()).isTrue();
        var consume = "/tenants/" + e2e.tenant("alpha") + "/customer-account-links";
        e2e.call("POST", consume, "outsider", null, Map.of("credential", issued.get("credential").asString()), 403);

        var fresh = e2e.call("POST", issuePath, "staff", null, SeededE2E.operation(), 200);
        e2e.call("POST", "/tenants/" + e2e.tenant("beta") + "/customer-account-links", "outsider", null,
                Map.of("credential", fresh.get("credential").asString()), 403);
        e2e.call("GET", "/orders/" + UUID.randomUUID(), "outsider", "alpha", null, 403);
        e2e.call("POST", consume, "outsider", null, Map.of("credential", fresh.get("credential").asString()), 200);
        e2e.call("POST", consume, "outsider", null, Map.of("credential", fresh.get("credential").asString()), 403);
        e2e.call("GET", "/orders/" + UUID.randomUUID(), "outsider", "alpha", null, 404);

        e2e.call("POST", issuePath, "customer", null, SeededE2E.operation(), 403);
        e2e.call("POST", "/administration/tenants/" + e2e.tenant("beta") + "/customers/" + customer + "/account-link-proofs",
                "beta-admin", null, SeededE2E.operation(), 403);
        var outstanding = SeededE2E.find(e2e.manifest.get("outstanding").get("customerAccountLinkProofs"), "tenant", "alpha");
        assertThat(e2e.call("DELETE", "/administration/tenants/" + e2e.tenant("alpha") + "/customer-account-link-proofs/"
                + outstanding.get("proofId").asString(), "staff", null, null, 200).get("changed").asBoolean()).isTrue();
    }

    @Test @Order(4)
    void initialStaffCeremonyEstablishesTheFirstStaffOfTheEmptyTenantOnce() throws Exception {
        var path = "/administration/tenants/" + e2e.tenant("epsilon") + "/initial-staff-provisioning";
        e2e.call("GET", "/catalog/products", "unbound", "epsilon", null, 401);
        e2e.call("POST", path, "staff", null, SeededE2E.operation(), 403);
        e2e.call("POST", "/administration/tenants/" + e2e.tenant("alpha") + "/initial-staff-provisioning", "platform", null,
                SeededE2E.operation(), 403);
        var first = e2e.call("POST", path, "platform", null, SeededE2E.operation(), 200);
        assertThat(e2e.call("DELETE", path + "/" + first.get("intentId").asString(), "platform", null, null, 200)
                .get("changed").asBoolean()).isTrue();
        e2e.call("POST", "/identity/bootstrap/staff", "unbound", null, Map.of("credential", first.get("credential").asString()), 403);
        var request = SeededE2E.operation();
        var issued = e2e.call("POST", path, "platform", null, request, 200);
        assertThat(e2e.call("POST", path, "platform", null, request, 200).has("credential")).isFalse();
        e2e.read(e2e.raw("POST", "/identity/bootstrap/staff", null, null, Map.of("credential", issued.get("credential").asString()), null), 401);
        e2e.call("POST", "/identity/bootstrap/staff", "unbound", null, Map.of("credential", "not-a-valid-credential-value-000000000000"), 403);
        e2e.call("POST", "/identity/bootstrap/staff", "unbound", null, Map.of("credential", issued.get("credential").asString()), 200);
        e2e.call("POST", "/identity/bootstrap/staff", "unbound", null, Map.of("credential", issued.get("credential").asString()), 403);
        e2e.call("GET", "/catalog/products", "unbound", "epsilon", null, 200);
        e2e.call("GET", "/catalog/products", "unbound", "alpha", null, 403);
        e2e.call("POST", path, "platform", null, SeededE2E.operation(), 403);
    }

    private static HashMap<String, Object> withOperation(Map<String, Object> request) {
        var copy = new HashMap<String, Object>(request);
        copy.putAll(SeededE2E.operation());
        return copy;
    }
}

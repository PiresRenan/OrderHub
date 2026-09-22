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
 * Why: a User may add or remove sign-in identities without gaining or losing unrelated authority.
 * Covers: link proof issue/replay/cancel, bootstrap external-link consumption by the unbound identity (which then
 * authenticates as the same internal User), reuse and bound-identity rejection, account listing, unlinking another
 * User's binding and the last-binding guard.
 * Prevents: identity takeover, proofs that survive cancellation and self-lockout.
 */
class SeededExternalIdentityE2ETest {
    private static SeededE2E e2e;

    @BeforeAll static void start() throws Exception { e2e = SeededE2E.start(); }

    @AfterAll static void stop() { if (e2e != null) e2e.close(); }

    @Test
    void externalIdentityLinkLifecycleKeepsOneInternalUser() throws Exception {
        assertThat(e2e.call("GET", "/identity/external-accounts", "customer", null, null, 200).size()).isEqualTo(1);
        e2e.read(e2e.raw("GET", "/identity/external-accounts", null, null, null, null), 401);
        e2e.call("GET", "/identity/external-accounts", "unbound", null, null, 401);

        var request = SeededE2E.operation();
        var cancelled = e2e.call("POST", "/identity/external-link-proofs", "customer", null, request, 200);
        assertThat(e2e.call("POST", "/identity/external-link-proofs", "customer", null, request, 200).has("credential")).isFalse();
        var cancel = "/identity/external-link-proofs/" + cancelled.get("proofId").asString();
        // Another User's proof is indistinguishable from an absent one: a no-op, never a cancellation.
        assertThat(e2e.call("DELETE", cancel, "outsider", null, null, 200).get("changed").asBoolean()).isFalse();
        assertThat(e2e.call("DELETE", "/identity/external-link-proofs/" + UUID.randomUUID(), "outsider", null, null, 200)
                .get("changed").asBoolean()).isFalse();
        assertThat(e2e.call("DELETE", cancel, "customer", null, null, 200).get("changed").asBoolean()).isTrue();
        e2e.call("POST", "/identity/bootstrap/external-links", "unbound", null, Map.of("credential", cancelled.get("credential").asString()), 403);

        var proof = e2e.call("POST", "/identity/external-link-proofs", "customer", null, SeededE2E.operation(), 200);
        var credential = Map.of("credential", proof.get("credential").asString());
        e2e.read(e2e.raw("POST", "/identity/bootstrap/external-links", null, null, credential, null), 401);
        e2e.call("POST", "/identity/bootstrap/external-links", "outsider", null, credential, 403);
        e2e.call("POST", "/identity/bootstrap/external-links", "unbound", null, credential, 200);
        e2e.call("POST", "/identity/bootstrap/external-links", "unbound", null, credential, 403);

        var accounts = e2e.call("GET", "/identity/external-accounts", "customer", null, null, 200);
        assertThat(accounts.size()).isEqualTo(2);
        assertThat(e2e.call("GET", "/identity/external-accounts", "unbound", null, null, 200)).isEqualTo(accounts);
        // The newly linked identity is the same internal User: it sees that User's Order and nothing more.
        var order = e2e.seededOrder("alpha-single-item").get("orderId").asString();
        e2e.call("GET", "/orders/" + order, "unbound", "alpha", null, 200);
        e2e.call("GET", "/catalog/products", "unbound", "alpha", null, 403);

        var linked = binding(accounts, e2e.jdbc.queryForObject("SELECT binding_id FROM users.external_identity_bindings WHERE subject = ?",
                UUID.class, DevelopmentIssuer.subject("unbound")));
        assertThat(e2e.call("DELETE", "/identity/external-accounts/" + linked, "outsider", null, null, 200)
                .get("changed").asBoolean()).isFalse();
        assertThat(e2e.call("DELETE", "/identity/external-accounts/" + linked, "customer", null, null, 200).get("changed").asBoolean()).isTrue();
        e2e.call("GET", "/identity/external-accounts", "unbound", null, null, 401);
        var remaining = e2e.call("GET", "/identity/external-accounts", "customer", null, null, 200);
        assertThat(remaining.size()).isEqualTo(1);
        e2e.call("DELETE", "/identity/external-accounts/" + remaining.get(0).get("bindingId").asString(), "customer", null, null, 403);
        assertThat(e2e.call("GET", "/identity/external-accounts", "customer", null, null, 200).size()).isEqualTo(1);
    }

    private static String binding(JsonNode accounts, UUID expected) {
        return StreamSupport.stream(accounts.spliterator(), false).map(node -> node.get("bindingId").asString())
                .filter(id -> id.equals(expected.toString())).findFirst().orElseThrow();
    }
}

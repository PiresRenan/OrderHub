package io.github.piresrenan.orderhub.development;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.stream.StreamSupport;

import org.junit.jupiter.api.Test;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.node.ObjectNode;

/**
 * Why: developers need a rich, trustworthy dataset without manual SQL.
 * Covers: the published manifest, every persona's real access over HTTP and restart determinism.
 * Prevents: an undocumented, partial or tenant-trivial development fixture.
 */
class DevelopmentSeedContractTest {
    private static final Set<String> PERSONAS = Set.of("platform", "staff", "beta-admin", "multi-tenant-staff",
            "alpha-member-no-role", "alpha-suspended", "alpha-terminated", "org-viewer", "customer",
            "alpha-customer-2", "beta-customer", "outsider", "unbound");
    /** Documented, ordered top-level manifest fields (docs/development/seed-data.md). */
    static final List<String> MANIFEST_FIELDS = List.of("schemaVersion", "issuer", "tenantId", "productId", "variantId",
            "customerId", "personas", "organizations", "tenants", "customers", "catalog", "inventory", "orders", "outstanding");
    private static final Set<String> SECRET_FIELDS = Set.of("credential", "access_token", "token", "password",
            "privateKey", "secret", "d", "p", "q");
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void manifestDescribesANonTrivialMultiTenantScenarioWithoutSecrets() throws Exception {
        try (var context = LocalDevelopmentApplication.start(0, 0)) {
            var manifest = manifest(context);
            assertThat(manifest.get("schemaVersion").asInt()).isEqualTo(1);
            assertThat(List.copyOf(manifest.propertyNames())).containsExactlyElementsOf(MANIFEST_FIELDS);
            assertThat(names(manifest.get("personas"), "name")).containsExactlyInAnyOrderElementsOf(PERSONAS);
            assertThat(names(manifest.get("organizations"), "key")).containsExactlyInAnyOrder("north", "south", "dormant");
            assertThat(names(manifest.get("tenants"), "key")).containsExactlyInAnyOrder("alpha", "beta", "gamma", "delta", "epsilon");
            assertThat(find(manifest.get("organizations"), "key", "dormant").get("status").asString()).isEqualTo("SUSPENDED");
            assertThat(names(find(manifest.get("organizations"), "key", "north").get("tenants"))).containsExactlyInAnyOrder("alpha", "beta");
            assertThat(names(find(manifest.get("organizations"), "key", "south").get("tenants"))).containsExactlyInAnyOrder("gamma", "epsilon");
            var delta = find(manifest.get("tenants"), "key", "delta");
            assertThat(delta.get("status").asString()).isEqualTo("SUSPENDED");
            assertThat(delta.get("organization").isNull()).isTrue();
            assertThat(find(manifest.get("tenants"), "key", "alpha").get("inventoryPolicy").asString()).isEqualTo("DENY");
            assertThat(find(manifest.get("tenants"), "key", "beta").get("inventoryPolicy").asString()).isEqualTo("ALLOW_BACKORDER");
            assertThat(find(manifest.get("tenants"), "key", "epsilon").get("staffPlacement").isNull()).isTrue();

            var catalog = manifest.get("catalog");
            for (var tenant : List.of("alpha", "beta")) {
                var entries = catalog.get(tenant);
                assertThat(entries.get("categories").size()).isEqualTo(4);
                assertThat(names(entries.get("products"), "status")).contains("DRAFT", "ACTIVE", "ARCHIVED");
                assertThat(names(entries.get("variants"), "status")).contains("DRAFT", "ACTIVE", "INACTIVE", "ARCHIVED");
            }
            // Deliberate tenant-scoped identifier overlap makes cross-tenant leakage detectable.
            var alphaSkus = names(catalog.get("alpha").get("variants"), "sku");
            var betaSkus = names(catalog.get("beta").get("variants"), "sku");
            assertThat(betaSkus).containsAll(List.of("PAPER-A4", "PEN-BLUE"));
            assertThat(alphaSkus).containsAll(List.of("PAPER-A4", "PEN-BLUE"));
            assertThat(find(catalog.get("alpha").get("variants"), "sku", "PAPER-A4").get("id"))
                    .isNotEqualTo(find(catalog.get("beta").get("variants"), "sku", "PAPER-A4").get("id"));

            assertThat(manifest.get("customers").size()).isEqualTo(6);
            assertThat(manifest.get("orders").size()).isEqualTo(4);
            assertThat(names(manifest.get("orders"), "allocation"))
                    .contains("FULLY_ALLOCATED", "PARTIALLY_BACKORDERED", "FULLY_BACKORDERED");
            assertThat(manifest.get("outstanding").get("customerAccountLinkProofs").size()).isEqualTo(1);
            assertThat(manifest.get("outstanding").get("staffProvisioningIntents").size()).isEqualTo(1);
            // Legacy selectors used by existing onboarding examples remain available.
            for (var legacy : List.of("tenantId", "productId", "variantId", "customerId")) {
                assertThat(manifest.hasNonNull(legacy)).isTrue();
            }
            assertThat(fieldNames(manifest)).doesNotContainAnyElementsOf(SECRET_FIELDS);
        }
    }

    @Test
    void everyPersonaIsIssuableAndHasExactlyItsDocumentedTenantAccess() throws Exception {
        try (var context = LocalDevelopmentApplication.start(0, 0)) {
            var manifest = manifest(context);
            var app = app(context);
            var issuer = context.getBean(DevelopmentIssuer.class).baseUri();
            for (var persona : manifest.get("personas")) {
                var token = token(issuer, persona.get("name").asString());
                for (var tenant : manifest.get("tenants")) {
                    var access = persona.get("tenantAccess").path(tenant.get("key").asString()).asString("NONE");
                    var status = request("GET", app + "/catalog/products", token, tenant.get("id").asString()).statusCode();
                    var expected = switch (access) {
                        case "STAFF_GOVERNANCE" -> 200;
                        case "UNBOUND" -> 401;
                        default -> 403;
                    };
                    assertThat(status).as("%s in %s (%s)", persona.get("name").asString(), tenant.get("key").asString(), access)
                            .isEqualTo(expected);
                }
            }
            for (var order : manifest.get("orders")) {
                var owner = token(issuer, order.get("persona").asString());
                var tenant = find(manifest.get("tenants"), "key", order.get("tenant").asString()).get("id").asString();
                assertThat(request("GET", app + "/orders/" + order.get("orderId").asString(), owner, tenant).statusCode()).isEqualTo(200);
            }
        }
    }

    @Test
    void manifestIdentifiersAndQuantitiesMatchDurableState() throws Exception {
        try (var context = LocalDevelopmentApplication.start(0, 0)) {
            var manifest = manifest(context);
            var app = app(context);
            var jdbc = new JdbcTemplate(context.getBean(javax.sql.DataSource.class));
            var issuer = context.getBean(DevelopmentIssuer.class).baseUri();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM tenants.tenants", Integer.class)).isEqualTo(5);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM organizations.organizations", Integer.class)).isEqualTo(3);
            for (var entry : Map.of("alpha", "staff", "beta", "beta-admin").entrySet()) {
                var token = token(issuer, entry.getValue());
                var tenant = find(manifest.get("tenants"), "key", entry.getKey()).get("id").asString();
                for (var product : manifest.get("catalog").get(entry.getKey()).get("products")) {
                    var body = read(request("GET", app + "/catalog/products/" + product.get("id").asString(), token, tenant), 200);
                    assertThat(body.get("status").asString()).isEqualTo(product.get("status").asString());
                }
                for (var position : manifest.get("inventory").get(entry.getKey())) {
                    var body = read(request("GET", app + "/inventory/positions/" + position.get("variantId").asString(), token, tenant), 200);
                    assertThat(body.get("onHand").asLong()).isEqualTo(position.get("onHand").asLong());
                    assertThat(body.get("committed").asLong()).isEqualTo(position.get("committed").asLong());
                }
            }
            assertThat(jdbc.queryForObject("SELECT count(*) FROM orders.orders", Integer.class)).isEqualTo(4);
            assertThat(jdbc.queryForObject("SELECT count(*) FROM customers.customer_profiles", Integer.class)).isEqualTo(6);
        }
    }

    @Test
    void restartProducesTheSameLogicalScenario() throws Exception {
        JsonNode first;
        try (var context = LocalDevelopmentApplication.start(0, 0)) {
            first = normalized(manifest(context));
        }
        try (var context = LocalDevelopmentApplication.start(0, 0)) {
            assertThat(normalized(manifest(context))).isEqualTo(first);
        }
    }

    /** Removes only product-generated identifiers; client-chosen identifiers and all semantics must be identical. */
    private JsonNode normalized(JsonNode manifest) {
        var copy = (ObjectNode) manifest.deepCopy();
        copy.remove(List.of("issuer", "tenantId"));
        copy.get("organizations").forEach(node -> ((ObjectNode) node).remove("id"));
        copy.get("tenants").forEach(node -> ((ObjectNode) node).remove(List.of("id", "staffPlacement")));
        copy.get("personas").forEach(node -> ((ObjectNode) node).remove("userId"));
        copy.get("orders").forEach(node -> ((ObjectNode) node).remove("orderId"));
        copy.get("outstanding").get("customerAccountLinkProofs").forEach(node -> ((ObjectNode) node).remove("proofId"));
        copy.get("outstanding").get("staffProvisioningIntents").forEach(node -> ((ObjectNode) node).remove("intentId"));
        return copy;
    }

    private JsonNode manifest(org.springframework.context.ConfigurableApplicationContext context) throws Exception {
        return read(request("GET", context.getBean(DevelopmentIssuer.class).baseUri() + "/fixture", null, null), 200);
    }

    private static String app(org.springframework.context.ConfigurableApplicationContext context) {
        return "http://127.0.0.1:" + ((WebServerApplicationContext) context).getWebServer().getPort();
    }

    private String token(String issuer, String persona) throws Exception {
        var response = client.send(HttpRequest.newBuilder(URI.create(issuer + "/tokens/" + persona))
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.noBody()).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(response.statusCode()).as("token for %s", persona).isEqualTo(200);
        return json.readTree(response.body()).get("access_token").asString();
    }

    private HttpResponse<String> request(String method, String url, String token, String tenant) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (tenant != null) builder.header("X-Tenant-Id", tenant);
        return client.send(builder.method(method, HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
    }

    private JsonNode read(HttpResponse<String> response, int status) throws Exception {
        assertThat(response.statusCode()).as("%s %s", response.request().method(), response.uri().getPath()).isEqualTo(status);
        return json.readTree(response.body());
    }

    private static JsonNode find(JsonNode array, String field, String value) {
        return StreamSupport.stream(array.spliterator(), false)
                .filter(node -> value.equals(node.path(field).asString())).findFirst()
                .orElseThrow(() -> new AssertionError("Missing " + field + "=" + value));
    }

    private static List<String> names(JsonNode array, String field) {
        return StreamSupport.stream(array.spliterator(), false).map(node -> node.path(field).asString()).toList();
    }

    private static List<String> names(JsonNode array) {
        return StreamSupport.stream(array.spliterator(), false).map(node -> node.asString()).toList();
    }

    private static Set<String> fieldNames(JsonNode node) {
        var names = new TreeMap<String, Boolean>();
        collect(node, names);
        return names.keySet();
    }

    private static void collect(JsonNode node, Map<String, Boolean> names) {
        if (node.isObject()) {
            for (var name : new ArrayList<>(node.propertyNames())) {
                names.put(name, true);
                collect(node.get(name), names);
            }
        } else if (node.isArray()) {
            node.forEach(child -> collect(child, names));
        }
    }
}

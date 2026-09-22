package io.github.piresrenan.orderhub.development;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.Map;
import java.util.UUID;
import java.util.stream.StreamSupport;

import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Real-socket client for the seeded development launcher used by the v1 end-to-end suites.
 *
 * <p>Every call crosses HTTP, Spring Security, JWT verification against the loopback issuer, internal identity,
 * Tenant resolution, authorization, the application service, a transaction and PostgreSQL. JDBC is exposed only for
 * asserting durable effects, never for performing the action under test.
 */
final class SeededE2E implements AutoCloseable {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper json = new ObjectMapper();
    private final ConfigurableApplicationContext context;
    final String app;
    final String issuer;
    final JsonNode manifest;
    final JdbcTemplate jdbc;

    private SeededE2E(ConfigurableApplicationContext context) throws Exception {
        this.context = context;
        this.app = "http://127.0.0.1:" + ((WebServerApplicationContext) context).getWebServer().getPort();
        this.issuer = context.getBean(DevelopmentIssuer.class).baseUri();
        this.jdbc = new JdbcTemplate(context.getBean(javax.sql.DataSource.class));
        this.manifest = json.readTree(send("GET", issuer + "/fixture", null, null, null, null).body());
    }

    static SeededE2E start() throws Exception {
        return new SeededE2E(LocalDevelopmentApplication.start(0, 0));
    }

    /** Short-lived bearer for a catalogued persona, requested on demand from the loopback issuer. */
    String token(String persona) throws Exception {
        var response = send("POST", issuer + "/tokens/" + persona, null, null, "", null);
        assertThat(response.statusCode()).as("token for %s", persona).isEqualTo(200);
        return json.readTree(response.body()).get("access_token").asString();
    }

    /** Calls the application as a persona (or anonymously when null) and asserts the exact status. */
    JsonNode call(String method, String path, String persona, String tenantKey, Object body, int status) throws Exception {
        return read(raw(method, path, persona == null ? null : token(persona), tenantKey == null ? null : tenant(tenantKey), body, null), status);
    }

    /** Same as {@link #call} with an explicit raw bearer and Tenant selector. */
    HttpResponse<String> raw(String method, String path, String bearer, String tenantId, Object body, String idempotencyKey)
            throws Exception {
        return send(method, app + path, bearer, tenantId, body == null ? null : json.writeValueAsString(body), idempotencyKey);
    }

    JsonNode order(String persona, String tenantKey, String key, Object body, int status) throws Exception {
        return read(raw("POST", "/orders", token(persona), tenant(tenantKey), body, key), status);
    }

    JsonNode read(HttpResponse<String> response, int status) throws Exception {
        // Diagnostics deliberately exclude bearer and proof material.
        assertThat(response.statusCode()).as("%s %s", response.request().method(), response.uri().getPath()).isEqualTo(status);
        return response.body().isBlank() ? json.nullNode() : json.readTree(response.body());
    }

    String tenant(String key) { return find(manifest.get("tenants"), "key", key).get("id").asString(); }

    String organization(String key) { return find(manifest.get("organizations"), "key", key).get("id").asString(); }

    String user(String persona) { return find(manifest.get("personas"), "name", persona).get("userId").asString(); }

    String customer(String key) { return find(manifest.get("customers"), "key", key).get("customerId").asString(); }

    JsonNode seededOrder(String key) { return find(manifest.get("orders"), "key", key); }

    String variant(String tenantKey, String sku) { return find(manifest.get("catalog").get(tenantKey).get("variants"), "sku", sku).get("id").asString(); }

    String product(String tenantKey, String key) { return find(manifest.get("catalog").get(tenantKey).get("products"), "key", key).get("id").asString(); }

    String category(String tenantKey, String key) { return find(manifest.get("catalog").get(tenantKey).get("categories"), "key", key).get("id").asString(); }

    JsonNode placement(String tenantKey) { return find(manifest.get("tenants"), "key", tenantKey).get("staffPlacement"); }

    static Map<String, Object> operation() {
        return Map.of("operationId", UUID.randomUUID(), "correlationId", UUID.randomUUID());
    }

    static JsonNode find(JsonNode array, String field, String value) {
        return StreamSupport.stream(array.spliterator(), false).filter(node -> value.equals(node.path(field).asString()))
                .findFirst().orElseThrow(() -> new AssertionError("Missing " + field + "=" + value));
    }

    private HttpResponse<String> send(String method, String url, String bearer, String tenantId, String body,
            String idempotencyKey) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15));
        if (bearer != null) builder.header("Authorization", "Bearer " + bearer);
        if (tenantId != null) builder.header("X-Tenant-Id", tenantId);
        if (idempotencyKey != null) builder.header("Idempotency-Key", idempotencyKey);
        if (body != null) builder.header("Content-Type", "application/json");
        return client.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    @Override public void close() { context.close(); }
}

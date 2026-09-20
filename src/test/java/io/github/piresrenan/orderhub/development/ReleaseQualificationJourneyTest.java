package io.github.piresrenan.orderhub.development;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.piresrenan.orderhub.customers.domain.model.CustomerProfile;
import io.github.piresrenan.orderhub.users.application.port.in.ResolveExternalIdentityQuery;
import io.github.piresrenan.orderhub.users.application.port.in.ResolveExternalIdentityUseCase;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

/**
 * Why: endpoint tests alone cannot qualify a usable release journey.
 * Covers: real HTTP/JWT Platform, first Staff, catalog, stock, Customer linking,
 * Orders, scope isolation and suspension with durable state/evidence readback.
 * Prevents: fixture shortcuts being mistaken for publicly available onboarding.
 * The disposable fixture supplies the explicit Platform trust root and bound
 * Customer identity; CustomerProfile creation is an admitted fixture-only step.
 */
class ReleaseQualificationJourneyTest {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void platformToFirstStaffToCustomerOrderPreservesAuthorityAndDurableEffects() throws Exception {
        try (var context = LocalDevelopmentApplication.start(0, 0)) {
            var app = "http://127.0.0.1:" + ((WebServerApplicationContext) context).getWebServer().getPort();
            var issuer = context.getBean(DevelopmentIssuer.class).baseUri();
            var jdbc = new JdbcTemplate(context.getBean(javax.sql.DataSource.class));
            var identities = context.getBean(ResolveExternalIdentityUseCase.class);
            var fixture = read(request("GET", issuer + "/fixture", null, null, null), 200);
            var originalTenant = fixture.get("tenantId").asText();
            var platform = token(issuer, "platform");
            var staff = token(issuer, "outsider");
            var customer = token(issuer, "customer");
            var organizationViewer = token(issuer, "staff");

            // The fixed local issuer has no arbitrary-subject endpoint. Make its
            // unused outsider identity unbound in this owned disposable fixture.
            // No production command or credential bypass is used after this setup.
            assertThat(jdbc.update("DELETE FROM users.external_identity_bindings WHERE issuer = ? AND subject = ?",
                    issuer, DevelopmentIssuer.subject("outsider"))).isEqualTo(1);
            assertThat(identities.resolve(new ResolveExternalIdentityQuery(issuer,
                    DevelopmentIssuer.subject("outsider")))).isEmpty();

            var organization = call(app, "POST", "/platform/organizations", platform, null,
                    Map.of("name", "Release qualification organization"), 201).get("id").asText();
            var unrelatedOrganization = call(app, "POST", "/platform/organizations", platform, null,
                    Map.of("name", "Unrelated qualification organization"), 201).get("id").asText();
            var tenant = call(app, "POST", "/platform/tenants", platform, null,
                    Map.of("name", "Release qualification tenant"), 201).get("id").asText();
            var tenantId = UUID.fromString(tenant);
            call(app, "PUT", "/platform/organizations/" + organization + "/tenants/" + tenant,
                    platform, null, null, 204);
            assertThat(jdbc.queryForObject("SELECT organization_id FROM organizations.tenant_placements WHERE tenant_id = ?",
                    UUID.class, tenantId)).isEqualTo(UUID.fromString(organization));

            call(app, "GET", "/catalog/products", staff, tenant, null, 401);
            var issuanceBody = operation();
            var issuePath = "/administration/tenants/" + tenant + "/initial-staff-provisioning";
            var proofResponse = request("POST", app + issuePath, platform, null, json.writeValueAsString(issuanceBody));
            var proof = read(proofResponse, 200);
            assertThat(proofResponse.headers().firstValue("Cache-Control")).contains("no-store");
            var issuanceReplay = call(app, "POST", issuePath, platform, null, issuanceBody, 200);
            assertThat(issuanceReplay.has("credential")).isFalse();
            assertThat(issuanceReplay.get("intentId")).isEqualTo(proof.get("intentId"));
            var staffCredential = Map.of("credential", proof.get("credential").asText());
            call(app, "POST", "/identity/bootstrap/staff", staff, null, staffCredential, 200);
            call(app, "POST", "/identity/bootstrap/staff", staff, null, staffCredential, 403);
            call(app, "GET", "/catalog/products", staff, tenant, null, 200);
            call(app, "GET", "/platform/organizations", staff, null, null, 403);
            call(app, "GET", "/catalog/products", platform, tenant, null, 403);
            assertThat(count(jdbc, "SELECT count(*) FROM workforce.staff_profiles WHERE tenant_id = ?", tenantId)).isEqualTo(1);
            assertThat(count(jdbc, "SELECT count(*) FROM workforce.provisioning_events WHERE tenant_id = ? AND action = 'CONSUMED'", tenantId)).isEqualTo(1);

            // Grant metadata visibility to a User who has Staff authority only in
            // the original fixture tenant; it must not unlock this tenant's data.
            var viewerId = identities.resolve(new ResolveExternalIdentityQuery(issuer,
                    DevelopmentIssuer.subject("staff"))).orElseThrow().userId();
            call(app, "PUT", "/platform/organizations/" + organization + "/administrative-grants/"
                    + viewerId + "/permissions/ORGANIZATION_TENANTS_VIEW", platform, null, null, 204);
            var listed = call(app, "GET", "/organizations/" + organization + "/tenants",
                    organizationViewer, null, null, 200);
            assertThat(listed.isArray()).isTrue();
            assertThat(listed.size()).isEqualTo(1);
            assertThat(listed.get(0).get("id").asText()).isEqualTo(tenant);
            call(app, "GET", "/organizations/" + unrelatedOrganization + "/tenants",
                    organizationViewer, null, null, 404); // Owner anti-enumeration contract.
            call(app, "GET", "/inventory/positions", organizationViewer, tenant, null, 403);

            var categoryId = UUID.randomUUID();
            var productId = UUID.randomUUID();
            var variantId = UUID.randomUUID();
            call(app, "POST", "/catalog/categories", staff, tenant,
                    Map.of("id", categoryId, "name", "Qualification category", "slug", "qualification-category"), 201);
            var product = call(app, "POST", "/catalog/products", staff, tenant,
                    Map.of("id", productId, "name", "Qualification notebook", "slug", "qualification-notebook"), 201);
            product = call(app, "PUT", "/catalog/products/" + productId + "/categories", staff, tenant,
                    Map.of("expectedRevision", product.get("revision").asLong(), "categoryIds", List.of(categoryId)), 200);
            var variant = call(app, "POST", "/catalog/products/" + productId + "/variants", staff, tenant,
                    Map.of("id", variantId, "sku", "QUALIFICATION-NOTEBOOK", "attributes", List.of()), 201);
            variant = call(app, "POST", "/catalog/variants/" + variantId + "/activate", staff, tenant,
                    Map.of("expectedRevision", variant.get("revision").asLong()), 200);
            product = call(app, "POST", "/catalog/products/" + productId + "/activate", staff, tenant,
                    Map.of("expectedRevision", product.get("revision").asLong()), 200);
            assertThat(product.get("status").asText()).isEqualTo("ACTIVE");
            assertThat(variant.get("status").asText()).isEqualTo("ACTIVE");
            call(app, "PUT", "/catalog/variants/" + variantId + "/prices/BRL", staff, tenant,
                    Map.of("expectedRevision", 0, "minorUnits", 1290), 200);
            var price = call(app, "GET", "/catalog/variants/" + variantId + "/prices/BRL", staff, tenant, null, 200);
            assertThat(price.get("minorUnits").asLong()).isEqualTo(1290);
            assertThat(call(app, "GET", "/catalog/products/" + productId, staff, tenant, null, 200)
                    .get("categoryIds").get(0).asText()).isEqualTo(categoryId.toString());
            call(app, "GET", "/catalog/products", staff, tenant, null, 200);
            call(app, "GET", "/catalog/products/" + productId, organizationViewer, originalTenant, null, 404);
            call(app, "PUT", "/catalog/variants/" + variantId + "/prices/BRL", staff, tenant,
                    Map.of("expectedRevision", 0, "minorUnits", 1500), 409);

            call(app, "PUT", "/inventory/policy", staff, tenant,
                    Map.of("policy", "DENY", "reason", "RELEASE_QUALIFICATION"), 200);
            var receipt = Map.of("operationId", UUID.randomUUID(), "variantId", variantId,
                    "quantity", 10, "reason", "RELEASE_QUALIFICATION");
            var movement = call(app, "POST", "/inventory/receipts", staff, tenant, receipt, 201);
            assertThat(call(app, "POST", "/inventory/receipts", staff, tenant, receipt, 201)).isEqualTo(movement);
            call(app, "POST", "/inventory/adjustments", staff, tenant,
                    Map.of("operationId", UUID.randomUUID(), "variantId", variantId,
                            "delta", -1, "reason", "RELEASE_QUALIFICATION"), 201);
            var stockPath = "/inventory/positions/" + variantId;
            call(app, "PUT", stockPath + "/safety-stock", staff, tenant,
                    Map.of("expectedSafetyStock", 0, "safetyStock", 1, "reason", "RELEASE_QUALIFICATION"), 200);
            var position = call(app, "GET", stockPath, staff, tenant, null, 200);
            assertThat(position.get("onHand").asLong()).isEqualTo(9);
            assertThat(position.get("safetyStock").asLong()).isEqualTo(1);
            assertThat(count(jdbc, "SELECT count(*) FROM inventory.movements WHERE tenant_id = ?", tenantId)).isEqualTo(2);

            // Explicit existing-v1 precondition: no CustomerProfile creation API
            // or application command exists. Only this reference row is seeded;
            // proof issuance, ownership and membership are established over HTTP.
            var customerProfile = new CustomerProfile(tenantId, UUID.randomUUID());
            jdbc.update("INSERT INTO customers.customer_profiles (tenant_id, customer_id) VALUES (?, ?)",
                    customerProfile.tenantId(), customerProfile.customerId());
            call(app, "GET", "/orders/" + UUID.randomUUID(), customer, tenant, null, 403);
            var customerProof = call(app, "POST", "/administration/tenants/" + tenant + "/customers/"
                    + customerProfile.customerId() + "/account-link-proofs", staff, null, operation(), 200);
            call(app, "POST", "/tenants/" + tenant + "/customer-account-links", customer, null,
                    Map.of("credential", customerProof.get("credential").asText()), 200);
            call(app, "GET", stockPath, customer, tenant, null, 403);
            assertThat(count(jdbc, "SELECT count(*) FROM customers.account_link_events WHERE tenant_id = ? AND action = 'CONSUMED'", tenantId)).isEqualTo(1);

            var orderBody = order(customerProfile.customerId(), variantId, 2);
            var idempotencyKey = "qualification-" + UUID.randomUUID();
            var first = read(createOrder(app, customer, tenant, orderBody, idempotencyKey), 201);
            assertThat(read(createOrder(app, customer, tenant, orderBody, idempotencyKey), 201)).isEqualTo(first);
            var orderPath = "/orders/" + first.get("id").asText();
            call(app, "GET", orderPath, customer, tenant, null, 200);
            // Same Customer User is bound in both tenants: changing only the
            // selector must still hide the order that belongs to this tenant.
            call(app, "GET", orderPath, customer, originalTenant, null, 404);
            read(createOrder(app, customer, tenant, order(customerProfile.customerId(), variantId, 3), idempotencyKey), 422);
            read(createOrder(app, staff, tenant, orderBody, "staff-" + UUID.randomUUID()), 403);
            var insufficientKey = "insufficient-" + UUID.randomUUID();
            read(createOrder(app, customer, tenant, order(customerProfile.customerId(), variantId, 7), insufficientKey), 409);
            assertThat(count(jdbc, "SELECT count(*) FROM orders.orders WHERE tenant_id = ?", tenantId)).isEqualTo(1);
            assertThat(count(jdbc, "SELECT count(*) FROM orders.order_request_idempotency WHERE tenant_id = ? AND state = 'COMPLETED'", tenantId)).isEqualTo(1);
            assertThat(count(jdbc, "SELECT count(*) FROM orders.order_request_idempotency WHERE tenant_id = ? AND state = 'PROCESSING'", tenantId)).isZero();
            assertThat(count(jdbc, "SELECT count(*) FROM inventory.inventory_commitments WHERE tenant_id = ?", tenantId)).isEqualTo(1);
            assertThat(call(app, "GET", stockPath, staff, tenant, null, 200).get("committed").asLong()).isEqualTo(2);

            call(app, "PUT", "/platform/tenants/" + tenant + "/suspension", platform, null, null, 204);
            call(app, "GET", orderPath, customer, tenant, null, 403);
            call(app, "GET", stockPath, staff, tenant, null, 403);
            read(createOrder(app, customer, tenant, orderBody, idempotencyKey), 403);
            call(app, "DELETE", "/platform/tenants/" + tenant + "/suspension", platform, null, null, 204);
            call(app, "GET", orderPath, customer, tenant, null, 200);
            assertThat(read(createOrder(app, customer, tenant, orderBody, idempotencyKey), 201)).isEqualTo(first);
            assertThat(jdbc.queryForList("SELECT action_type FROM tenants.administrative_audit_events WHERE tenant_id = ? ORDER BY occurred_at, audit_event_id",
                    String.class, tenantId)).containsExactly("CREATE_TENANT", "SUSPEND_TENANT", "RECOVER_TENANT");
            assertThat(count(jdbc, "SELECT count(*) FROM catalog.administrative_audit_events WHERE tenant_id = ?", tenantId)).isGreaterThanOrEqualTo(7);
            assertThat(count(jdbc, "SELECT count(*) FROM inventory.policy_changes WHERE tenant_id = ?", tenantId)).isEqualTo(2);
            assertThat(count(jdbc, "SELECT count(*) FROM orders.orders WHERE tenant_id = ?", tenantId)).isEqualTo(1);
            call(app, "GET", "/readyz", null, null, null, 200);
        }
    }

    private Map<String, Object> operation() {
        return Map.of("operationId", UUID.randomUUID(), "correlationId", UUID.randomUUID());
    }

    private Map<String, Object> order(UUID customer, UUID variant, int quantity) {
        return Map.of("customerId", customer, "items", List.of(Map.of("variantId", variant, "quantity", quantity)));
    }

    private int count(JdbcTemplate jdbc, String sql, UUID tenant) {
        return jdbc.queryForObject(sql, Integer.class, tenant);
    }

    private String token(String issuer, String persona) throws Exception {
        return read(request("POST", issuer + "/tokens/" + persona, null, null, ""), 200).get("access_token").asText();
    }

    private JsonNode call(String app, String method, String path, String token, String tenant,
            Object body, int status) throws Exception {
        return read(request(method, app + path, token, tenant,
                body == null ? null : json.writeValueAsString(body)), status);
    }

    private JsonNode read(HttpResponse<String> response, int status) throws Exception {
        // Assertion diagnostics deliberately exclude bearer/proof/response body.
        assertThat(response.statusCode()).as("%s %s", response.request().method(), response.uri().getPath()).isEqualTo(status);
        return response.body().isBlank() ? null : json.readTree(response.body());
    }

    private HttpResponse<String> createOrder(String app, String token, String tenant, Object body, String key) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(app + "/orders"))
                .timeout(Duration.ofSeconds(15)).header("Authorization", "Bearer " + token)
                .header("X-Tenant-Id", tenant).header("Idempotency-Key", key)
                .header("Content-Type", "application/json")
                .POST(HttpRequest.BodyPublishers.ofString(json.writeValueAsString(body))).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> request(String method, String url, String token, String tenant, String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (tenant != null) builder.header("X-Tenant-Id", tenant);
        if (body != null) builder.header("Content-Type", "application/json");
        return client.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody()
                : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
}

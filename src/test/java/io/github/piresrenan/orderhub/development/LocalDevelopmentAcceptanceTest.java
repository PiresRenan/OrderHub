package io.github.piresrenan.orderhub.development;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.boot.web.server.context.WebServerApplicationContext;
import tools.jackson.databind.ObjectMapper;

/** Why: onboarding needs real trust boundaries; Covers: owned fixtures and HTTP flows; Prevents: unsafe or unusable demo shortcuts. */
class LocalDevelopmentAcceptanceTest {
    private final HttpClient client = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).build();
    private final ObjectMapper json = new ObjectMapper();

    @Test
    void ambientProductionTokenProfileCannotReplaceOwnedSyntheticTrust() throws Exception {
        // Why: the local issuer is generic; Covers: production profile leakage;
        // Prevents: an operator environment making the disposable launcher unusable.
        var key = "orderhub.security.jwt.token-profile";
        var previous = System.getProperty(key);
        try {
            System.setProperty(key, "COGNITO");
            try (var context = LocalDevelopmentApplication.start(0, 0)) {
                assertThat(context.getBean(io.github.piresrenan.orderhub.security.adapter.in.authentication.jwt.JwtResourceServerProperties.class)
                        .tokenProfile()).isEqualTo(io.github.piresrenan.orderhub.security.adapter.in.authentication.jwt.JwtTokenProfile.GENERIC);
            }
        } finally {
            if (previous == null) System.clearProperty(key); else System.setProperty(key, previous);
        }
    }

    @Test
    void ambientHikariConnectionSettingsCannotRedirectFixtureWrites() throws Exception {
        var key = "spring.datasource.hikari.jdbc-url";
        var previous = System.getProperty(key);
        try {
            System.setProperty(key, "jdbc:postgresql://127.0.0.1:1/must_not_connect");
            try (var context = LocalDevelopmentApplication.start(0, 0)) {
                var source = context.getBean(javax.sql.DataSource.class);
                try (var connection = source.getConnection()) {
                    assertThat(connection.getCatalog()).isEqualTo("orderhub_development");
                }
            }
        } finally {
            if (previous == null) System.clearProperty(key); else System.setProperty(key, previous);
        }
    }

    @Test
    void isolatedFixtureSupportsAuthorizedStockAndCustomerOrderReplay() throws Exception {
        try (var context = LocalDevelopmentApplication.start(0, 0)) {
            var app = "http://127.0.0.1:" + ((WebServerApplicationContext) context).getWebServer().getPort();
            var issuerBean = context.getBean("developmentIssuer");
            var issuer = (String) issuerBean.getClass().getMethod("baseUri").invoke(issuerBean);
            var fixtureResponse = request("GET", issuer + "/fixture", null, null, null);
            assertThat(fixtureResponse.statusCode()).isEqualTo(200);
            var fixture = json.readTree(fixtureResponse.body());
            var tenant = fixture.get("tenantId").asString();
            var variant = fixture.get("variantId").asString();
            var customer = fixture.get("customerId").asString();
            var staffToken = token(issuer, "staff");
            var customerToken = token(issuer, "customer");
            var outsiderToken = token(issuer, "outsider");
            assertThat(request("GET", app + "/readyz", null, null, null).statusCode()).isEqualTo(200);
            assertThat(request("GET", app + "/catalog/products/" + fixture.get("productId").asString(), staffToken, tenant, null).statusCode()).isEqualTo(200);
            assertThat(request("GET", app + "/inventory/positions/" + variant, customerToken, tenant, null).statusCode()).isEqualTo(403);
            assertThat(request("GET", app + "/inventory/positions/" + variant, outsiderToken, tenant, null).statusCode()).isEqualTo(403);

            var receipt = "{\"operationId\":\"" + UUID.randomUUID() + "\",\"variantId\":\"" + variant + "\",\"quantity\":5,\"reason\":\"LOCAL_RECEIPT\"}";
            assertThat(request("POST", app + "/inventory/receipts", staffToken, tenant, receipt).statusCode()).isEqualTo(201);
            var stock = json.readTree(request("GET", app + "/inventory/positions/" + variant, staffToken, tenant, null).body());
            assertThat(stock.get("onHand").asLong()).isEqualTo(105);

            var orderBody = "{\"customerId\":\"" + customer + "\",\"items\":[{\"variantId\":\"" + variant + "\",\"quantity\":2}]}";
            var key = "local-acceptance-" + UUID.randomUUID();
            var first = createOrder(app, customerToken, tenant, orderBody, key);
            assertThat(first.statusCode()).isEqualTo(201);
            var replay = createOrder(app, customerToken, tenant, orderBody, key);
            assertThat(replay.statusCode()).isEqualTo(201);
            assertThat(json.readTree(replay.body())).isEqualTo(json.readTree(first.body()));
            assertThat(createOrder(app, staffToken, tenant, orderBody, "staff-denied-" + key).statusCode()).isEqualTo(403);
            var after = json.readTree(request("GET", app + "/inventory/positions/" + variant, staffToken, tenant, null).body());
            assertThat(after.get("committed").asLong()).isEqualTo(2);
            assertThat(request("GET", app + "/fixture", null, null, null).statusCode()).isEqualTo(401);
            assertThat(request("GET", app + "/tokens/customer", null, null, null).statusCode()).isEqualTo(401);
            assertThat(request("GET", issuer + "/tokens/customer", null, null, null).statusCode()).isEqualTo(405);
            assertThat(request("POST", issuer + "/tokens/arbitrary", null, null, "").statusCode()).isEqualTo(404);
            var crossOrigin = client.send(HttpRequest.newBuilder(URI.create(issuer + "/tokens/customer"))
                    .header("Origin", "https://untrusted.example").POST(HttpRequest.BodyPublishers.noBody()).build(), HttpResponse.BodyHandlers.ofString());
            assertThat(crossOrigin.statusCode()).isEqualTo(403);
        }
    }

    private String token(String issuer, String persona) throws Exception {
        var response = request("POST", issuer + "/tokens/" + persona, null, null, "");
        assertThat(response.statusCode()).isEqualTo(200);
        assertThat(response.headers().firstValue("Cache-Control")).contains("no-store");
        return json.readTree(response.body()).get("access_token").asString();
    }

    private HttpResponse<String> createOrder(String app, String token, String tenant, String body, String key) throws Exception {
        return client.send(HttpRequest.newBuilder(URI.create(app + "/orders"))
                .timeout(Duration.ofSeconds(15)).header("Authorization", "Bearer " + token)
                .header("X-Tenant-Id", tenant).header("Idempotency-Key", key)
                .header("Content-Type", "application/json").POST(HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }

    private HttpResponse<String> request(String method, String url, String token, String tenant, String body) throws Exception {
        var builder = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofSeconds(15));
        if (token != null) builder.header("Authorization", "Bearer " + token);
        if (tenant != null) builder.header("X-Tenant-Id", tenant);
        if (body != null) builder.header("Content-Type", "application/json");
        return client.send(builder.method(method, body == null ? HttpRequest.BodyPublishers.noBody() : HttpRequest.BodyPublishers.ofString(body)).build(), HttpResponse.BodyHandlers.ofString());
    }
}

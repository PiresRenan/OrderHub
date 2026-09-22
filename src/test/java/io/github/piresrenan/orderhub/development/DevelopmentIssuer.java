package io.github.piresrenan.orderhub.development;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import io.github.piresrenan.orderhub.security.support.RealJwtTestSupport;
import tools.jackson.databind.ObjectMapper;

/** Ephemeral loopback fixture issuer, deliberately excluded from the application HTTP surface. */
public final class DevelopmentIssuer implements AutoCloseable {
    static final String AUDIENCE = "orderhub-disposable-development";
    private static final Set<String> PERSONAS = Set.copyOf(DevelopmentSeedCatalog.PERSONA_NAMES);
    private final RSAKey key = RealJwtTestSupport.generateRsaKey("disposable-development");
    private final ObjectMapper json = new ObjectMapper();
    private final HttpServer server;
    private final AtomicReference<Map<String, Object>> fixture = new AtomicReference<>();

    DevelopmentIssuer(int port) throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", port), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    /** Returns only a public loopback address, never key material or a credential. */
    public String baseUri() { return "http://127.0.0.1:" + server.getAddress().getPort(); }

    static String subject(String persona) { return "synthetic-local-" + persona; }

    /** Publishes the complete manifest once; insertion order is kept so its structure is deterministic. */
    void ready(Map<String, Object> selectors) { fixture.set(java.util.Collections.unmodifiableMap(new java.util.LinkedHashMap<>(selectors))); }

    private void handle(HttpExchange exchange) throws IOException {
        try (exchange) {
            // Exact Host rejects DNS rebinding; no browser Origin is admitted and no CORS headers are returned.
            var host = "127.0.0.1:" + server.getAddress().getPort();
            if (!host.equals(exchange.getRequestHeaders().getFirst("Host"))
                    || exchange.getRequestHeaders().containsKey("Origin")) {
                respond(exchange, 403, Map.of("error", "request_not_allowed"));
                return;
            }
            var path = exchange.getRequestURI().getPath();
            if (exchange.getRequestURI().getRawQuery() != null) {
                respond(exchange, 400, Map.of("error", "query_not_supported"));
            } else if (path.equals("/jwks")) {
                if (!requireMethod(exchange, "GET")) return;
                bytes(exchange, 200, new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8));
            } else if (path.equals("/fixture")) {
                if (!requireMethod(exchange, "GET")) return;
                if (fixture.get() == null) respond(exchange, 503, Map.of("error", "fixture_not_ready"));
                else respond(exchange, 200, fixture.get());
            } else if (path.startsWith("/tokens/")) {
                if (!requireMethod(exchange, "POST")) return;
                if (!"application/json".equalsIgnoreCase(exchange.getRequestHeaders().getFirst("Content-Type"))) {
                    respond(exchange, 415, Map.of("error", "empty_json_request_required"));
                    return;
                }
                if (exchange.getRequestBody().read() != -1) {
                    respond(exchange, 400, Map.of("error", "body_not_supported"));
                    return;
                }
                var persona = path.substring("/tokens/".length());
                if (!PERSONAS.contains(persona)) respond(exchange, 404, Map.of("error", "unknown_persona"));
                else if (fixture.get() == null) respond(exchange, 503, Map.of("error", "fixture_not_ready"));
                else {
                    try {
                        var token = RealJwtTestSupport.signedToken(key, baseUri(), subject(persona), AUDIENCE,
                                Instant.now().plusSeconds(300), Instant.now().minusSeconds(5));
                        respond(exchange, 200, Map.of("access_token", token, "token_type", "Bearer", "expires_in", 300));
                    } catch (Exception failure) {
                        respond(exchange, 500, Map.of("error", "token_unavailable"));
                    }
                }
            } else respond(exchange, 404, Map.of("error", "not_found"));
        }
    }

    private boolean requireMethod(HttpExchange exchange, String method) throws IOException {
        if (method.equals(exchange.getRequestMethod())) return true;
        exchange.getResponseHeaders().set("Allow", method);
        respond(exchange, 405, Map.of("error", "method_not_allowed"));
        return false;
    }

    private void respond(HttpExchange exchange, int status, Object body) throws IOException {
        bytes(exchange, status, json.writeValueAsBytes(body));
    }

    private void bytes(HttpExchange exchange, int status, byte[] body) throws IOException {
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.getResponseHeaders().set("Cache-Control", "no-store");
        exchange.getResponseHeaders().set("X-Content-Type-Options", "nosniff");
        exchange.sendResponseHeaders(status, body.length);
        exchange.getResponseBody().write(body);
    }

    @Override public void close() { server.stop(0); }
}

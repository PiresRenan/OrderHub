package io.github.piresrenan.orderhub.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.security.test.web.servlet.setup.SecurityMockMvcConfigurers.springSecurity;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;

import java.net.InetSocketAddress;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.Date;
import java.util.Optional;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;

import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.gen.RSAKeyGenerator;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.runner.WebApplicationContextRunner;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.ResponseEntity;
import org.springframework.security.config.annotation.web.configuration.EnableWebSecurity;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtException;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.config.annotation.EnableWebMvc;

import io.github.piresrenan.orderhub.tenants.application.port.in.operational.FindTenantOperationalStateUseCase;
import io.github.piresrenan.orderhub.tenants.application.port.in.operational.TenantOperationalState;
import io.github.piresrenan.orderhub.users.application.port.in.IsTenantMembershipOperationallyActiveUseCase;
import io.github.piresrenan.orderhub.users.application.port.in.ResolveExternalIdentityUseCase;
import io.github.piresrenan.orderhub.users.application.port.in.ResolvedUserIdentity;
import io.github.piresrenan.orderhub.users.application.port.out.ExternalIdentityBindingPersistenceException;

/** Real signed tokens and the production configured decoder/chains, without database fixtures. */
class ReleaseSecurityBoundaryTest {
    private static final String ISSUER = "https://issuer.example.test";
    private static final String OTHER = "https://other.example.test";
    private static final String AUDIENCE = "https://api.example.test";
    private static final String ORIGIN = "https://frontend.example.test";
    private static final UUID USER = UUID.fromString("00000000-0000-0000-0000-000000000001");
    private RSAKey key;
    private HttpServer server;
    private String jwks;
    private final AtomicInteger keyRequests = new AtomicInteger();
    private final AtomicInteger bindings = new AtomicInteger();
    private boolean identityUnavailable;
    private boolean identityDefect;

    @BeforeEach void start() throws Exception {
        key = new RSAKeyGenerator(2048).keyID("synthetic-release-key").generate();
        var body = new JWKSet(key.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/jwks", exchange -> {
            keyRequests.incrementAndGet();
            exchange.getResponseHeaders().add("Content-Type", "application/json");
            exchange.sendResponseHeaders(200, body.length);
            try (var output = exchange.getResponseBody()) { output.write(body); }
        });
        server.start();
        jwks = "http://127.0.0.1:" + server.getAddress().getPort() + "/jwks";
    }
    @AfterEach void stop() { server.stop(0); }

    @Test void trustedPreflightReachesNeitherIdentityNorKeysOnAllApiChains() {
        runner().withPropertyValues("orderhub.security.cors.allowed-origins[0]=" + ORIGIN).run(context -> {
            var mvc = mvc(context.getSourceApplicationContext());
            for (var path : new String[]{"/orders", "/identity/bootstrap/staff", "/identity/bootstrap/external-links"}) {
                mvc.perform(options(path).header("Origin", ORIGIN)
                        .header("Access-Control-Request-Method", "POST")
                        .header("Access-Control-Request-Headers", "authorization,content-type,x-tenant-id,idempotency-key"))
                        .andExpect(status().isOk()).andExpect(header().string("Access-Control-Allow-Origin", ORIGIN));
            }
            assertThat(bindings).hasValue(0);
            assertThat(keyRequests).hasValue(0);
        });
    }

    @Test void configuredDecoderRejectsSignedTokenWithoutExpiryAndBothHttpChainsDenyIt() {
        runner().run(context -> {
            var token = token(ISSUER, AUDIENCE, null, null);
            assertThatThrownBy(() -> context.getBean(JwtDecoder.class).decode(token)).isInstanceOf(JwtException.class);
            var mvc = mvc(context.getSourceApplicationContext());
            for (var path : new String[]{"/orders", "/identity/bootstrap/staff", "/identity/bootstrap/external-links"}) {
                mvc.perform(post(path).header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized())
                        .andExpect(header().string("WWW-Authenticate", "Bearer"));
            }
            assertThat(bindings).hasValue(0);
        });
    }

    @Test void configuredCognitoDecoderRejectsIdTokenBeforeOrdinaryOrBootstrapIdentity() {
        runner().withPropertyValues("orderhub.security.jwt.token-profile=COGNITO", "orderhub.security.jwt.allowed-client-ids[0]=spa-client").run(context -> {
            var token = token(ISSUER, AUDIENCE, "id", Instant.now().plusSeconds(300), "spa-client");
            assertThatThrownBy(() -> context.getBean(JwtDecoder.class).decode(token)).isInstanceOf(JwtException.class);
            var mvc = mvc(context.getSourceApplicationContext());
            for (var path : new String[]{"/orders", "/identity/bootstrap/staff", "/identity/bootstrap/external-links"}) {
                mvc.perform(post(path).header("Authorization", "Bearer " + token)).andExpect(status().isUnauthorized());
            }
            assertThat(bindings).hasValue(0);
        });
    }

    @Test void missingExpiryIsRejectedByOrdinaryHttpChain() {
        runner().run(context -> mvc(context.getSourceApplicationContext()).perform(post("/orders")
                .header("Authorization", "Bearer " + token(ISSUER, AUDIENCE, null, null)))
                .andExpect(status().isUnauthorized()));
    }

    @Test void idTokenIsRejectedByBothBootstrapHttpPaths() {
        runner().withPropertyValues("orderhub.security.jwt.token-profile=COGNITO", "orderhub.security.jwt.allowed-client-ids[0]=spa-client").run(context -> {
            var mvc = mvc(context.getSourceApplicationContext());
            for (var path : new String[]{"/identity/bootstrap/staff", "/identity/bootstrap/external-links"}) {
                mvc.perform(post(path).header("Authorization", "Bearer " + token(ISSUER, AUDIENCE, "id", Instant.now().plusSeconds(300), "spa-client")))
                        .andExpect(status().isUnauthorized());
            }
        });
    }

    @Test void allowedOriginCanReadSuccessAndAuthenticationErrorsWithoutCookieCredentials() {
        runner().withPropertyValues("orderhub.security.cors.allowed-origins[0]=" + ORIGIN).run(context -> {
            var mvc = mvc(context.getSourceApplicationContext());
            mvc.perform(post("/orders").header("Origin", ORIGIN))
                    .andExpect(status().isUnauthorized()).andExpect(header().string("Access-Control-Allow-Origin", ORIGIN))
                    .andExpect(header().doesNotExist("Access-Control-Allow-Credentials"))
                    .andExpect(header().string("Cache-Control", "no-store"));
            mvc.perform(post("/orders").header("Origin", ORIGIN)
                    .header("Authorization", "Bearer " + token(ISSUER, AUDIENCE, null, Instant.now().plusSeconds(300))))
                    .andExpect(status().isCreated()).andExpect(header().string("Access-Control-Allow-Origin", ORIGIN))
                    .andExpect(header().string("Access-Control-Expose-Headers", "Location, WWW-Authenticate, Retry-After"));
            mvc.perform(get("/orders/denied").header("Origin", ORIGIN)
                    .header("Authorization", "Bearer " + token(ISSUER, AUDIENCE, null, Instant.now().plusSeconds(300))))
                    .andExpect(status().isForbidden()).andExpect(header().string("Access-Control-Allow-Origin", ORIGIN));
        });
    }

    @Test void untrustedOriginsHeadersAndMethodsCannotReachIdentity() {
        runner().withPropertyValues("orderhub.security.cors.allowed-origins[0]=" + ORIGIN).run(context -> {
            var mvc = mvc(context.getSourceApplicationContext());
            for (var origin : new String[]{"https://attacker.example.test", "null"}) {
                mvc.perform(options("/orders").header("Origin", origin).header("Access-Control-Request-Method", "POST"))
                        .andExpect(status().isForbidden()).andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
            }
            mvc.perform(options("/orders").header("Origin", ORIGIN).header("Access-Control-Request-Method", "TRACE"))
                    .andExpect(status().isForbidden());
            mvc.perform(options("/orders").header("Origin", ORIGIN).header("Access-Control-Request-Method", "POST")
                    .header("Access-Control-Request-Headers", "X-Magic-Authority")).andExpect(status().isForbidden());
            mvc.perform(options("/actuator/health").header("Origin", ORIGIN).header("Access-Control-Request-Method", "GET"))
                    .andExpect(header().doesNotExist("Access-Control-Allow-Origin"));
            assertThat(bindings).hasValue(0);
            assertThat(keyRequests).hasValue(0);
        });
        runner().run(context -> mvc(context.getSourceApplicationContext()).perform(options("/orders")
                .header("Origin", ORIGIN).header("Access-Control-Request-Method", "POST")).andExpect(status().isForbidden()));
    }

    @Test void malformedOriginConfigurationFailsClosed() {
        for (var origin : new String[]{"*", "null", "https://*.example.test", "https://example.test/", "https://user@example.test",
                "http://example.test", "https://example.test?q=a", "https://example.test#fragment", "https://example.test:0",
                "https://example.test:443", "http://localhost:80", "http://127.0.0.1:80", "http://[::1]:80"}) {
            runner().withPropertyValues("orderhub.security.cors.allowed-origins[0]=" + origin)
                    .run(context -> assertThat(context).hasFailed());
        }
    }

    @Test void mixedIssuerProfilesRetainGenericCompatibilityWithoutWeakeningCognito() {
        for (var cognitoPrimary : new boolean[]{true, false}) {
            runner().withPropertyValues("orderhub.security.jwt.token-profile=" + (cognitoPrimary ? "COGNITO" : "GENERIC"),
                    "orderhub.security.jwt.additional-issuers[0].issuer=" + OTHER,
                    "orderhub.security.jwt.additional-issuers[0].jwk-set-uri=" + jwks,
                    "orderhub.security.jwt.additional-issuers[0].token-profile=" + (cognitoPrimary ? "GENERIC" : "COGNITO"),
                    (cognitoPrimary ? "orderhub.security.jwt" : "orderhub.security.jwt.additional-issuers[0]") + ".allowed-client-ids[0]=spa-client")
                    .run(context -> {
                        var decoder = context.getBean(JwtDecoder.class);
                        var cognito = cognitoPrimary ? ISSUER : OTHER;
                        var generic = cognitoPrimary ? OTHER : ISSUER;
                        var expiry = Instant.now().plusSeconds(300);
                        assertThat(decoder.decode(token(cognito, AUDIENCE, "access", expiry, "spa-client")).getSubject()).isEqualTo("synthetic-user");
                        assertThat(decoder.decode(token(generic, AUDIENCE, null, expiry)).getSubject()).isEqualTo("synthetic-user");
                        for (var purpose : new Object[]{null, "id", "refresh", java.util.List.of("access")}) {
                            assertThatThrownBy(() -> decoder.decode(token(cognito, AUDIENCE, purpose, expiry, "spa-client"))).isInstanceOf(JwtException.class);
                        }
                        assertThatThrownBy(() -> decoder.decode(token(generic, AUDIENCE, null, null))).isInstanceOf(JwtException.class);
                        assertThatThrownBy(() -> decoder.decode(token(cognito, "app-client-id", "access", expiry, "spa-client"))).isInstanceOf(JwtException.class);
                    });
        }
        runner().withPropertyValues("orderhub.security.jwt.token-profile=COGNITO", "orderhub.security.jwt.allowed-client-ids[0]=spa-client", "orderhub.security.jwt.audience=app-client-id")
                .run(context -> assertThat(context).hasFailed());
        runner().withPropertyValues("orderhub.security.jwt.token-profile=TYPO")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test void identityStoreFailureIsSanitizedServiceUnavailabilityRatherThanInvalidCredentials() {
        identityUnavailable = true;
        runner().withPropertyValues("orderhub.security.cors.allowed-origins[0]=" + ORIGIN).run(context -> {
            var response = mvc(context.getSourceApplicationContext()).perform(post("/orders")
                    .header("Origin", ORIGIN)
                    .header("Authorization", "Bearer " + token(ISSUER, AUDIENCE, null, Instant.now().plusSeconds(300))))
                    .andExpect(status().isServiceUnavailable())
                    .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(header().doesNotExist("WWW-Authenticate"))
                    .andExpect(header().string("Retry-After", "1"))
                    .andExpect(header().string("Access-Control-Expose-Headers", org.hamcrest.Matchers.containsString("Retry-After")))
                    .andExpect(jsonPath("$.code").value("authentication-unavailable")).andReturn().getResponse();
            assertThat(response.getContentAsString()).doesNotContain("synthetic-private", "Exception", "jdbc", "SQL");
        });
    }

    @Test void identityProgrammingDefectIsSanitizedInternalErrorWithoutRetryAdvice() {
        identityDefect = true;
        runner().run(context -> {
            var response = mvc(context.getSourceApplicationContext()).perform(post("/orders")
                    .header("Authorization", "Bearer " + token(ISSUER, AUDIENCE, null, Instant.now().plusSeconds(300))))
                    .andExpect(status().isInternalServerError())
                    .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                    .andExpect(header().string("Cache-Control", "no-store"))
                    .andExpect(header().doesNotExist("WWW-Authenticate"))
                    .andExpect(header().doesNotExist("Retry-After"))
                    .andExpect(jsonPath("$.code").value("authentication-failed")).andReturn().getResponse();
            assertThat(response.getContentAsString()).doesNotContain("synthetic-private", "Exception", "jdbc", "SQL");
        });
    }

    @Test void cognitoAdmitsOnlyConfiguredAppClientsAndNeverTreatsClientAsAudience() {
        runner().withPropertyValues("orderhub.security.jwt.token-profile=COGNITO",
                "orderhub.security.jwt.allowed-client-ids[0]=spa-client",
                "orderhub.security.jwt.allowed-client-ids[1]=mobile-client").run(context -> {
            var decoder = context.getBean(JwtDecoder.class);
            var expiry = Instant.now().plusSeconds(300);
            for (var client : new String[]{"spa-client", "mobile-client"}) {
                assertThat(decoder.decode(token(ISSUER, AUDIENCE, "access", expiry, client)).getSubject()).isEqualTo("synthetic-user");
            }
            for (var client : new Object[]{"foreign-client", null, 42, java.util.List.of("spa-client"), "SPA-CLIENT"}) {
                assertThatThrownBy(() -> decoder.decode(token(ISSUER, AUDIENCE, "access", expiry, client))).isInstanceOf(JwtException.class);
            }
            assertThatThrownBy(() -> decoder.decode(token(ISSUER, AUDIENCE, "id", expiry, "spa-client"))).isInstanceOf(JwtException.class);
            assertThatThrownBy(() -> decoder.decode(token(ISSUER, "spa-client", "access", expiry, "spa-client"))).isInstanceOf(JwtException.class);
            assertThatThrownBy(() -> decoder.decode(token(ISSUER, "https://other-api.example.test", "access", expiry, "spa-client"))).isInstanceOf(JwtException.class);
            assertThatThrownBy(() -> decoder.decode(token(ISSUER, AUDIENCE, "access", null, "spa-client"))).isInstanceOf(JwtException.class);
            mvc(context.getSourceApplicationContext()).perform(post("/orders")
                    .header("Authorization", "Bearer " + token(ISSUER, AUDIENCE, "access", expiry, "foreign-client")))
                    .andExpect(status().isUnauthorized()).andExpect(header().string("WWW-Authenticate", "Bearer"));
            assertThat(bindings).hasValue(0);
        });
    }

    @Test void genericProfileDoesNotRequireClientId() {
        runner().run(context -> assertThat(context.getBean(JwtDecoder.class)
                .decode(token(ISSUER, AUDIENCE, null, Instant.now().plusSeconds(300))).getSubject()).isEqualTo("synthetic-user"));
    }

    @Test void additionalCognitoIssuerUsesItsOwnClientAllowlist() {
        runner().withPropertyValues("orderhub.security.jwt.token-profile=COGNITO",
                "orderhub.security.jwt.allowed-client-ids[0]=primary-client",
                "orderhub.security.jwt.additional-issuers[0].issuer=" + OTHER,
                "orderhub.security.jwt.additional-issuers[0].jwk-set-uri=" + jwks,
                "orderhub.security.jwt.additional-issuers[0].token-profile=COGNITO",
                "orderhub.security.jwt.additional-issuers[0].allowed-client-ids[0]=other-client").run(context -> {
            var decoder = context.getBean(JwtDecoder.class);
            var expiry = Instant.now().plusSeconds(300);
            assertThat(decoder.decode(token(ISSUER, AUDIENCE, "access", expiry, "primary-client")).getSubject()).isEqualTo("synthetic-user");
            assertThat(decoder.decode(token(OTHER, AUDIENCE, "access", expiry, "other-client")).getSubject()).isEqualTo("synthetic-user");
            assertThatThrownBy(() -> decoder.decode(token(ISSUER, AUDIENCE, "access", expiry, "other-client"))).isInstanceOf(JwtException.class);
            assertThatThrownBy(() -> decoder.decode(token(OTHER, AUDIENCE, "access", expiry, "primary-client"))).isInstanceOf(JwtException.class);
        });
    }

    @Test void cognitoWithoutClientAllowlistFailsStartup() {
        runner().withPropertyValues("orderhub.security.jwt.token-profile=COGNITO")
                .run(context -> assertThat(context).hasFailed());
        runner().withPropertyValues("orderhub.security.jwt.additional-issuers[0].issuer=" + OTHER,
                "orderhub.security.jwt.additional-issuers[0].jwk-set-uri=" + jwks,
                "orderhub.security.jwt.additional-issuers[0].token-profile=COGNITO")
                .run(context -> assertThat(context).hasFailed());
    }

    @Test void omittedTokenProfileFailsStartupInsteadOfBecomingGeneric() {
        baseRunner().run(context -> assertThat(context).hasFailed());
        runner().withPropertyValues("orderhub.security.jwt.additional-issuers[0].issuer=" + OTHER,
                "orderhub.security.jwt.additional-issuers[0].jwk-set-uri=" + jwks)
                .run(context -> assertThat(context).hasFailed());
    }

    private WebApplicationContextRunner runner() {
        return baseRunner().withPropertyValues("orderhub.security.jwt.token-profile=GENERIC");
    }

    private WebApplicationContextRunner baseRunner() {
        return new WebApplicationContextRunner().withUserConfiguration(WebConfiguration.class, SecurityConfiguration.class)
                .withBean(ResolveExternalIdentityUseCase.class, () -> query -> {
                    if (identityDefect) throw new IllegalStateException("synthetic-private-invariant-diagnostic");
                    if (identityUnavailable) throw new ExternalIdentityBindingPersistenceException(new IllegalStateException("synthetic-private-database-diagnostic"));
                    bindings.incrementAndGet(); return Optional.of(new ResolvedUserIdentity(USER));
                })
                .withBean(IsTenantMembershipOperationallyActiveUseCase.class, () -> query -> true)
                .withBean(FindTenantOperationalStateUseCase.class, () -> query -> Optional.of(TenantOperationalState.ACTIVE))
                .withPropertyValues("orderhub.security.jwt.issuer=" + ISSUER,
                        "orderhub.security.jwt.audience=" + AUDIENCE,
                        "orderhub.security.jwt.jwk-set-uri=" + jwks);
    }
    private MockMvc mvc(org.springframework.web.context.WebApplicationContext context) {
        return MockMvcBuilders.webAppContextSetup(context).apply(springSecurity()).build();
    }
    private String token(String issuer, String audience, Object purpose, Instant expiry) throws Exception {
        return token(issuer, audience, purpose, expiry, null);
    }
    private String token(String issuer, String audience, Object purpose, Instant expiry, Object client) throws Exception {
        var claims = new JWTClaimsSet.Builder().issuer(issuer).subject("synthetic-user").audience(audience)
                .issueTime(Date.from(Instant.now().minusSeconds(5)));
        if (purpose != null) { claims.claim("token_use", purpose); }
        if (expiry != null) { claims.expirationTime(Date.from(expiry)); }
        if (client != null) { claims.claim("client_id", client); }
        var signed = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).keyID(key.getKeyID()).build(), claims.build());
        signed.sign(new RSASSASigner(key));
        return signed.serialize();
    }
    @Configuration(proxyBeanMethods = false)
    @EnableWebSecurity @EnableWebMvc
    static class WebConfiguration {
        @Bean Endpoint endpoint() { return new Endpoint(); }
    }
    @RestController static class Endpoint {
        @PostMapping({"/orders", "/identity/bootstrap/staff", "/identity/bootstrap/external-links"})
        ResponseEntity<Void> create() { return ResponseEntity.created(URI.create("/orders/synthetic")).build(); }
        @GetMapping("/orders") String read() { return "synthetic"; }
        @GetMapping("/orders/denied") String denied() { throw new org.springframework.security.access.AccessDeniedException("Synthetic denial"); }
    }
}

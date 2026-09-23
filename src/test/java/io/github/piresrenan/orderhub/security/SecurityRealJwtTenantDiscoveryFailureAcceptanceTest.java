package io.github.piresrenan.orderhub.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.reset;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import io.github.piresrenan.orderhub.security.support.RealJwtTestSupport;
import io.github.piresrenan.orderhub.support.PostgreSqlTestConfiguration;
import io.github.piresrenan.orderhub.tenants.application.port.in.operational.FindActiveTenantSummariesUseCase;
import io.github.piresrenan.orderhub.tenants.application.port.in.operational.TenantOperationalStateUnavailableException;
import io.github.piresrenan.orderhub.tenants.application.port.out.TenantPersistenceException;
import io.github.piresrenan.orderhub.users.application.port.in.OperationallyActiveMembershipTenantScan;
import io.github.piresrenan.orderhub.users.application.port.in.ResolveExternalIdentityQuery;
import io.github.piresrenan.orderhub.users.application.port.in.ResolveOrCreateExternalUserUseCase;
import io.github.piresrenan.orderhub.users.application.port.in.ScanOperationallyActiveMembershipTenantsUseCase;
import io.github.piresrenan.orderhub.users.application.port.in.TenantMembershipScanUnavailableException;
import io.github.piresrenan.orderhub.users.application.port.out.TenantMembershipPersistenceException;

/**
 * Why: discovery must fail closed; unavailable owner state must never look like "no Tenants" or an authentication
 * or authorization answer.
 * Scenario: a real signed JWT resolves a real bound User through the production security chain, then the Users
 * membership scan or the Tenants batch read is unavailable with a JDBC-shaped cause.
 * Covers: technical-failure mapping of GET /tenants after real authentication.
 * Expected: sanitized 500 Problem Details, never 200/401/403, with no SQL, schema, JDBC, subject, token or User id.
 * Prevents: fail-open discovery and persistence diagnostics leaking through Problem Details or logs.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgreSqlTestConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class SecurityRealJwtTenantDiscoveryFailureAcceptanceTest {
    private static final String ISSUER = "https://synthetic-discovery-failure-jwt.test";
    private static final String AUDIENCE = "orderhub-api";
    private static final String JDBC_DETAIL = "SELECT tenant_id FROM users.tenant_memberships jdbc:postgresql://db";
    private static final RSAKey KEY = key();
    private static final HttpServer JWK_SERVER = server();

    @Autowired private MockMvc mvc;
    @Autowired private ResolveOrCreateExternalUserUseCase users;
    @MockitoBean private ScanOperationallyActiveMembershipTenantsUseCase memberships;
    @MockitoBean private FindActiveTenantSummariesUseCase tenants;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("orderhub.security.jwt.token-profile", () -> "GENERIC");
        registry.add("orderhub.security.jwt.issuer", () -> ISSUER);
        registry.add("orderhub.security.jwt.audience", () -> AUDIENCE);
        registry.add("orderhub.security.jwt.jwk-set-uri", () -> "http://127.0.0.1:" + JWK_SERVER.getAddress().getPort() + "/jwks");
    }
    @AfterAll static void close() { JWK_SERVER.stop(0); }
    @BeforeEach void clean() { reset(memberships, tenants); }

    @Test void usersScanUnavailableIsSanitizedTechnicalFailure(CapturedOutput output) throws Exception {
        when(memberships.scan(any())).thenThrow(new TenantMembershipScanUnavailableException(
                new TenantMembershipPersistenceException(new DataAccessResourceFailureException(JDBC_DETAIL))));
        assertTechnicalFailure(output);
    }

    @Test void tenantsBatchUnavailableIsSanitizedTechnicalFailure(CapturedOutput output) throws Exception {
        when(memberships.scan(any())).thenReturn(new OperationallyActiveMembershipTenantScan(List.of(UUID.randomUUID()), false));
        when(tenants.find(any())).thenThrow(new TenantOperationalStateUnavailableException(
                new TenantPersistenceException(new DataAccessResourceFailureException(JDBC_DETAIL))));
        assertTechnicalFailure(output);
    }

    private void assertTechnicalFailure(CapturedOutput output) throws Exception {
        var subject = UUID.randomUUID().toString();
        var user = users.resolveOrCreate(new ResolveExternalIdentityQuery(ISSUER, subject)).userId();
        var token = RealJwtTestSupport.signedToken(KEY, ISSUER, subject, AUDIENCE, Instant.now().plusSeconds(300), Instant.now().minusSeconds(30));
        var body = mvc.perform(get("/tenants").header("Authorization", "Bearer " + token))
                .andExpect(status().isInternalServerError())
                .andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                .andExpect(jsonPath("$.code").value("tenant-discovery-technical-failure"))
                .andExpect(jsonPath("$.items").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain("tenant_memberships", "users.", "jdbc", "SELECT", "Exception", subject, token, user.toString());
        assertThat(output.getAll()).doesNotContain(subject, token);
    }

    private static RSAKey key() {
        try { return RealJwtTestSupport.generateRsaKey("discovery-failure-test-key"); }
        catch (Exception exception) { throw new ExceptionInInitializerError(exception); }
    }
    private static HttpServer server() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/jwks", exchange -> {
                var body = new JWKSet(KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, body.length);
                try (var out = exchange.getResponseBody()) { out.write(body); }
            });
            server.start(); return server;
        } catch (Exception exception) { throw new ExceptionInInitializerError(exception); }
    }
}

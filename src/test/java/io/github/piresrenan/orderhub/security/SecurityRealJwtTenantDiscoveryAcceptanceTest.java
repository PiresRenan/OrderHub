package io.github.piresrenan.orderhub.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.Date;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import com.jayway.jsonpath.JsonPath;
import com.nimbusds.jose.JOSEObjectType;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;

import io.github.piresrenan.orderhub.security.support.RealJwtTestSupport;
import io.github.piresrenan.orderhub.support.PostgreSqlTestConfiguration;
import io.github.piresrenan.orderhub.users.application.port.in.ResolveExternalIdentityQuery;
import io.github.piresrenan.orderhub.users.application.port.in.ResolveOrCreateExternalUserUseCase;
import io.github.piresrenan.orderhub.workforce.application.model.StaffProvisioningIssuance;
import io.github.piresrenan.orderhub.workforce.application.port.in.ColdStartStaffProvisioningUseCase;

/**
 * Why: clients need a safe way to learn which Tenant selectors they may use without
 * reading OrderHub persistence or trusting provider claims (ADR-0021).
 * Scenario: real signed JWTs through the production security chain against real PostgreSQL.
 * Covers: GET /tenants self-scoping, lifecycle filtering, bounded scan pagination and
 * the invariant that discovery never becomes Tenant authority.
 * Expected: only the caller's currently ACTIVE memberships in ACTIVE Tenants, as {id,name},
 * with no-store; later Tenant-scoped requests still revalidate current state.
 * Prevents: cross-user/cross-Tenant leakage, claim-derived membership, a Tenant-existence
 * oracle, authorization leases and filtering-induced 5xx.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgreSqlTestConfiguration.class)
@ExtendWith(OutputCaptureExtension.class)
class SecurityRealJwtTenantDiscoveryAcceptanceTest {
    private static final String ISSUER = "https://synthetic-discovery-jwt.test";
    private static final String SECOND_ISSUER = "https://synthetic-second-discovery-jwt.test";
    private static final String AUDIENCE = "orderhub-api";
    private static final RSAKey KEY = key("discovery-test-key");
    private static final RSAKey SECOND_KEY = key("second-discovery-test-key");
    private static final HttpServer JWK_SERVER = server();

    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ResolveOrCreateExternalUserUseCase users;
    @Autowired private ColdStartStaffProvisioningUseCase cold;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("orderhub.security.jwt.token-profile", () -> "GENERIC");
        registry.add("orderhub.security.jwt.issuer", () -> ISSUER);
        registry.add("orderhub.security.jwt.audience", () -> AUDIENCE);
        registry.add("orderhub.security.jwt.jwk-set-uri", () -> "http://127.0.0.1:" + JWK_SERVER.getAddress().getPort() + "/jwks");
        registry.add("orderhub.security.jwt.additional-issuers[0].token-profile", () -> "GENERIC");
        registry.add("orderhub.security.jwt.additional-issuers[0].issuer", () -> SECOND_ISSUER);
        registry.add("orderhub.security.jwt.additional-issuers[0].jwk-set-uri", () -> "http://127.0.0.1:" + JWK_SERVER.getAddress().getPort() + "/second-jwks");
    }
    @AfterAll static void close() { JWK_SERVER.stop(0); }

    // Matrix 1 + 10: the minimal projection, private cache policy, no authority metadata.
    @Test void singleActiveMembershipReturnsMinimalPrivateProjection() throws Exception {
        var subject = subject(); var user = user(ISSUER, subject); var tenant = tenant("Synthetic discovery one", "ACTIVE");
        membership(user, tenant, "ACTIVE");
        var body = mvc.perform(get("/tenants").header("Authorization", bearer(subject)))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(content().contentTypeCompatibleWith("application/json"))
                .andExpect(jsonPath("$.items.length()").value(1))
                .andExpect(jsonPath("$.items[0].id").value(tenant.toString()))
                .andExpect(jsonPath("$.items[0].name").value("Synthetic discovery one"))
                .andExpect(jsonPath("$.nextAfterId").doesNotExist())
                .andReturn().getResponse().getContentAsString();
        assertThat(JsonPath.<java.util.Map<String, Object>>read(body, "$.items[0]")).containsOnlyKeys("id", "name");
        assertThat(JsonPath.<java.util.Map<String, Object>>read(body, "$")).containsOnlyKeys("items", "nextAfterId");
        assertThat(body).doesNotContain(subject, ISSUER, user.toString(), "status", "role", "permission");
    }

    // Matrix 2, 4, 5, 6, 7: lifecycle filtering and foreign-Tenant isolation.
    @Test void onlyCurrentlySelectableOwnTenantsAreReturnedInIdentifierOrder() throws Exception {
        var subject = subject(); var user = user(ISSUER, subject);
        var active = List.of(tenant("Active A", "ACTIVE"), tenant("Active B", "ACTIVE"), tenant("Active C", "ACTIVE"));
        active.forEach(id -> membership(user, id, "ACTIVE"));
        membership(user, tenant("Suspended membership", "ACTIVE"), "SUSPENDED");
        membership(user, tenant("Terminated membership", "ACTIVE"), "TERMINATED");
        membership(user, tenant("Suspended tenant", "SUSPENDED"), "ACTIVE");
        var foreign = tenant("Foreign tenant", "ACTIVE"); membership(user(ISSUER, subject()), foreign, "ACTIVE");
        var body = mvc.perform(get("/tenants").header("Authorization", bearer(subject)))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(ids(body)).containsExactlyElementsOf(active.stream().sorted(Comparator.comparing(UUID::toString)).toList());
        assertThat(body).doesNotContain("Suspended", "Terminated", "Foreign", foreign.toString());
    }

    // Matrix 3.
    @Test void boundUserWithoutEligibleTenantReceivesEmptyFinalPage() throws Exception {
        var subject = subject(); user(ISSUER, subject);
        mvc.perform(get("/tenants").header("Authorization", bearer(subject)))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$.items.length()").value(0)).andExpect(jsonPath("$.nextAfterId").doesNotExist());
    }

    // Matrix 8 + 16: invalid bearer never reaches discovery and never reflects credentials.
    @Test void missingOrInvalidBearerIsSanitizedUnauthorized() throws Exception {
        mvc.perform(get("/tenants")).andExpect(status().isUnauthorized());
        var subject = subject(); var now = Instant.now();
        for (var token : List.of(
                RealJwtTestSupport.signedToken(key("untrusted-key"), ISSUER, subject, AUDIENCE, now.plusSeconds(300), now.minusSeconds(30)),
                RealJwtTestSupport.signedToken(KEY, "https://untrusted-issuer.test", subject, AUDIENCE, now.plusSeconds(300), now.minusSeconds(30)),
                RealJwtTestSupport.signedToken(KEY, ISSUER, subject, "other-audience", now.plusSeconds(300), now.minusSeconds(30)),
                RealJwtTestSupport.signedToken(KEY, ISSUER, subject, AUDIENCE, now.minusSeconds(300), now.minusSeconds(600)))) {
            var body = mvc.perform(get("/tenants").header("Authorization", "Bearer " + token))
                    .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
            assertThat(body).doesNotContain(token, subject);
        }
    }

    // Matrix 9 + 16: a verified but unbound identity keeps the existing non-enumerating 401 and creates nothing.
    @Test void verifiedUnboundIdentityKeepsExistingUnauthorizedContract(CapturedOutput output) throws Exception {
        var subject = subject();
        var body = mvc.perform(get("/tenants").header("Authorization", bearer(subject)))
                .andExpect(status().isUnauthorized()).andReturn().getResponse().getContentAsString();
        assertThat(body).doesNotContain(subject);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users.external_identity_bindings WHERE subject = ?", Integer.class, subject)).isZero();
        assertThat(output.getAll()).doesNotContain(subject);
    }

    // Matrix 11 + 18: provider claims and a second trusted issuer cannot manufacture membership.
    @Test void providerClaimsAndOtherIssuerBindingsNeverCreateMembership() throws Exception {
        var subject = subject(); var tenant = tenant("Claimed tenant", "ACTIVE");
        user(ISSUER, subject);
        var claimed = claimsToken(KEY, ISSUER, subject, tenant);
        mvc.perform(get("/tenants").header("Authorization", "Bearer " + claimed))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
        var other = user(SECOND_ISSUER, subject); membership(other, tenant, "ACTIVE");
        mvc.perform(get("/tenants").header("Authorization", bearer(subject)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
        mvc.perform(get("/tenants").header("Authorization", "Bearer " + token(SECOND_KEY, SECOND_ISSUER, subject)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(tenant.toString()));
    }

    // Matrix 12 + 14: limit bounds and malformed parameters are bounded 400s that reflect nothing.
    @Test void invalidPaginationParametersAreRejectedWithoutReflection() throws Exception {
        var subject = subject(); var user = user(ISSUER, subject);
        for (int i = 0; i < 3; i++) { membership(user, tenant("Bound " + i, "ACTIVE"), "ACTIVE"); }
        for (var query : List.of("limit=0", "limit=101", "limit=-1", "limit=abc", "afterId=not-a-uuid", "limit=999999999999")) {
            var body = mvc.perform(get("/tenants?" + query).header("Authorization", bearer(subject)))
                    .andExpect(status().isBadRequest()).andExpect(content().contentTypeCompatibleWith("application/problem+json"))
                    .andReturn().getResponse().getContentAsString();
            assertThat(body).doesNotContain("not-a-uuid", "abc", "999999999999", subject);
        }
        mvc.perform(get("/tenants?limit=1").header("Authorization", bearer(subject))).andExpect(status().isOk());
        mvc.perform(get("/tenants?limit=100").header("Authorization", bearer(subject))).andExpect(status().isOk());
    }

    // Matrix 12 + 13: exclusive deterministic cursor walks every eligible Tenant exactly once.
    @Test void cursorWalkIsDeterministicExclusiveAndComplete() throws Exception {
        var subject = subject(); var user = user(ISSUER, subject); var expected = new ArrayList<UUID>();
        for (int i = 0; i < 5; i++) { var id = tenant("Walk " + i, "ACTIVE"); membership(user, id, "ACTIVE"); expected.add(id); }
        expected.sort(Comparator.comparing(UUID::toString));
        var seen = new ArrayList<UUID>(); String cursor = null; int pages = 0;
        do {
            var body = page(subject, 2, cursor); var items = ids(body); seen.addAll(items); cursor = JsonPath.read(body, "$.nextAfterId");
            pages++;
            if (cursor != null) { assertThat(items).hasSize(2); assertThat(cursor).isEqualTo(items.get(1).toString()); }
        } while (cursor != null);
        assertThat(pages).isEqualTo(3);
        assertThat(seen).containsExactlyElementsOf(expected);
        assertThat(page(subject, 2, null)).isEqualTo(page(subject, 2, null));
        assertThat(ids(page(subject, 5, expected.get(4).toString()))).isEmpty();
    }

    // ADR-0021 bounded scan: filtered windows are empty/short pages with continuation, never 5xx.
    @Test void fullyFilteredScanWindowReturnsEmptyPageWithContinuation() throws Exception {
        var subject = subject(); var user = user(ISSUER, subject); var all = new ArrayList<UUID>();
        for (int i = 0; i < 6; i++) { var id = tenant("Window " + i, "ACTIVE"); membership(user, id, "ACTIVE"); all.add(id); }
        all.sort(Comparator.comparing(UUID::toString));
        for (var id : all.subList(0, 3)) { jdbc.update("UPDATE tenants.tenants SET status = 'SUSPENDED' WHERE id = ?", id); }
        var first = page(subject, 3, null);
        assertThat(ids(first)).isEmpty();
        assertThat(JsonPath.<String>read(first, "$.nextAfterId")).isEqualTo(all.get(2).toString());
        var second = page(subject, 3, all.get(2).toString());
        assertThat(ids(second)).containsExactlyElementsOf(all.subList(3, 6));
        assertThat(JsonPath.<String>read(second, "$.nextAfterId")).isNull();
        jdbc.update("UPDATE tenants.tenants SET status = 'SUSPENDED' WHERE id = ?", all.get(4));
        var shortPage = page(subject, 3, all.get(2).toString());
        assertThat(ids(shortPage)).containsExactly(all.get(3), all.get(5));
    }

    // ADR-0021 invariants A-G: limit+1 only detects continuation, the window is the first `limit` candidates,
    // nextAfterId is the last scanned candidate (never the last visible Tenant nor the limit+1 row), and only a
    // null cursor ends the scan.
    @Test void allInactiveWindowContinuesFromLastScannedCandidateThenReachesLaterTenant() throws Exception {
        var subject = subject(); var user = user(ISSUER, subject); var ordered = new ArrayList<UUID>();
        for (int i = 0; i < 4; i++) { var id = tenant("Scan " + i, "ACTIVE"); membership(user, id, "ACTIVE"); ordered.add(id); }
        ordered.sort(Comparator.comparing(UUID::toString));
        var a = ordered.get(0); var b = ordered.get(1); var c = ordered.get(2); var d = ordered.get(3);
        for (var id : List.of(a, b, c)) { jdbc.update("UPDATE tenants.tenants SET status = 'SUSPENDED' WHERE id = ?", id); }

        var first = page(subject, 3, null);
        assertThat(ids(first)).isEmpty();
        assertThat(JsonPath.<String>read(first, "$.nextAfterId")).isEqualTo(c.toString()).isNotEqualTo(d.toString());

        var second = page(subject, 3, c.toString());
        assertThat(ids(second)).containsExactly(d);
        assertThat(JsonPath.<String>read(second, "$.nextAfterId")).isNull();

        // A window with no later candidate is final: nextAfterId is null.
        jdbc.update("UPDATE users.tenant_memberships SET status = 'TERMINATED' WHERE user_id = ? AND tenant_id = ?", user, d);
        var exact = page(subject, 3, null);
        assertThat(ids(exact)).isEmpty();
        assertThat(JsonPath.<String>read(exact, "$.nextAfterId")).isNull();
    }

    // Matrix 7 + 11: disjoint Users with two memberships each, plus hostile authority claims, stay isolated.
    @Test void disjointUsersNeverSeeEachOtherEvenWithAuthorityClaims() throws Exception {
        var aSubject = subject(); var bSubject = subject(); var a = user(ISSUER, aSubject); var b = user(ISSUER, bSubject);
        var a1 = tenant("A1", "ACTIVE"); var a2 = tenant("A2", "ACTIVE"); var b1 = tenant("B1", "ACTIVE"); var b2 = tenant("B2", "ACTIVE");
        membership(a, a1, "ACTIVE"); membership(a, a2, "ACTIVE"); membership(b, b1, "ACTIVE"); membership(b, b2, "ACTIVE");
        var aBody = mvc.perform(get("/tenants").header("Authorization", "Bearer " + claimsToken(KEY, ISSUER, aSubject, b1))
                .header("X-Tenant-Id", b1)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        var bBody = mvc.perform(get("/tenants").header("Authorization", "Bearer " + claimsToken(KEY, ISSUER, bSubject, a1))
                .header("X-Tenant-Id", a1)).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        assertThat(ids(aBody)).containsExactlyInAnyOrder(a1, a2);
        assertThat(ids(bBody)).containsExactlyInAnyOrder(b1, b2);
    }

    // Matrix 17, 18, 20: discovery grants no lease; the next Tenant-scoped request revalidates.
    @Test void discoveryIsNeverAuthorityForLaterTenantScopedRequests() throws Exception {
        var fixture = staffFixture();
        mvc.perform(get("/tenants").header("Authorization", bearer(fixture.manager())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items[0].id").value(fixture.tenant().toString()));
        var scoped = get("/catalog/products?limit=1");
        mvc.perform(scoped.header("Authorization", bearer(fixture.manager())).header("X-Tenant-Id", fixture.tenant()))
                .andExpect(status().isOk());
        var foreign = tenant("Foreign selector", "ACTIVE");
        mvc.perform(get("/catalog/products?limit=1").header("Authorization", bearer(fixture.manager())).header("X-Tenant-Id", foreign))
                .andExpect(status().isForbidden());

        jdbc.update("UPDATE users.tenant_memberships SET status = 'SUSPENDED' WHERE user_id = ? AND tenant_id = ?", fixture.user(), fixture.tenant());
        mvc.perform(get("/catalog/products?limit=1").header("Authorization", bearer(fixture.manager())).header("X-Tenant-Id", fixture.tenant()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/tenants").header("Authorization", bearer(fixture.manager()))).andExpect(jsonPath("$.items.length()").value(0));

        jdbc.update("UPDATE users.tenant_memberships SET status = 'ACTIVE' WHERE user_id = ? AND tenant_id = ?", fixture.user(), fixture.tenant());
        jdbc.update("UPDATE tenants.tenants SET status = 'SUSPENDED' WHERE id = ?", fixture.tenant());
        mvc.perform(get("/catalog/products?limit=1").header("Authorization", bearer(fixture.manager())).header("X-Tenant-Id", fixture.tenant()))
                .andExpect(status().isForbidden());
        mvc.perform(get("/tenants").header("Authorization", bearer(fixture.manager()))).andExpect(jsonPath("$.items.length()").value(0));
    }

    // Matrix 19: concurrent disjoint Users never observe each other's Tenants.
    @Test void concurrentDisjointUsersHaveZeroCrossContamination() throws Exception {
        var aSubject = subject(); var bSubject = subject();
        var a = user(ISSUER, aSubject); var b = user(ISSUER, bSubject);
        var aTenant = tenant("Concurrent A", "ACTIVE"); var bTenant = tenant("Concurrent B", "ACTIVE");
        membership(a, aTenant, "ACTIVE"); membership(b, bTenant, "ACTIVE");
        var aToken = bearer(aSubject); var bToken = bearer(bSubject);
        int rounds = 40; var barrier = new CyclicBarrier(2);
        var executor = Executors.newFixedThreadPool(2);
        try {
            Callable<List<String>> runA = () -> run(barrier, rounds, aToken); Callable<List<String>> runB = () -> run(barrier, rounds, bToken);
            var fa = executor.submit(runA); var fb = executor.submit(runB);
            var aBodies = fa.get(60, TimeUnit.SECONDS); var bBodies = fb.get(60, TimeUnit.SECONDS);
            assertThat(aBodies).hasSize(rounds).allSatisfy(body -> assertThat(ids(body)).containsExactly(aTenant));
            assertThat(bBodies).hasSize(rounds).allSatisfy(body -> assertThat(ids(body)).containsExactly(bTenant));
        } finally { executor.shutdownNow(); }
    }

    private List<String> run(CyclicBarrier barrier, int rounds, String token) throws Exception {
        var bodies = new ArrayList<String>();
        for (int i = 0; i < rounds; i++) {
            barrier.await(10, TimeUnit.SECONDS);
            bodies.add(mvc.perform(get("/tenants").header("Authorization", token).header("X-Tenant-Id", UUID.randomUUID()))
                    .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
        }
        return bodies;
    }

    private String page(String subject, int limit, String afterId) throws Exception {
        var request = get("/tenants").param("limit", Integer.toString(limit)).header("Authorization", bearer(subject));
        if (afterId != null) { request.param("afterId", afterId); }
        return mvc.perform(request).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
    }
    private static List<UUID> ids(String body) {
        return JsonPath.<List<String>>read(body, "$.items[*].id").stream().map(UUID::fromString).toList();
    }
    private UUID user(String issuer, String subject) { return users.resolveOrCreate(new ResolveExternalIdentityQuery(issuer, subject)).userId(); }
    private UUID tenant(String name, String status) {
        var id = UUID.randomUUID(); jdbc.update("INSERT INTO tenants.tenants (id, name, status) VALUES (?, ?, ?)", id, name, status); return id;
    }
    private void membership(UUID user, UUID tenant, String status) {
        jdbc.update("INSERT INTO users.tenant_memberships (user_id, tenant_id, status) VALUES (?, ?, ?)", user, tenant, status);
    }
    private StaffFixture staffFixture() throws Exception {
        var platform = subject(); var actor = user(ISSUER, platform); var tenant = tenant("Synthetic discovery staff", "ACTIVE");
        jdbc.update("INSERT INTO access_control.administrative_grants (grant_id, user_id, scope_type, scope_id, permission_code) VALUES (?, ?, 'PLATFORM', NULL, 'PLATFORM_TENANTS_MANAGE')", UUID.randomUUID(), actor);
        var credential = ((StaffProvisioningIssuance.Issued) cold.issue(actor, tenant, UUID.randomUUID(), UUID.randomUUID())).credential();
        var manager = subject();
        mvc.perform(post("/identity/bootstrap/staff").header("Authorization", bearer(manager)).contentType("application/json")
                .content("{\"credential\":\"" + credential + "\"}")).andExpect(status().isOk());
        return new StaffFixture(tenant, manager, user(ISSUER, manager));
    }
    private record StaffFixture(UUID tenant, String manager, UUID user) {}

    private static String subject() { return UUID.randomUUID().toString(); }
    private static String bearer(String subject) throws Exception { return "Bearer " + token(KEY, ISSUER, subject); }
    private static String token(RSAKey key, String issuer, String subject) throws Exception {
        return RealJwtTestSupport.signedToken(key, issuer, subject, AUDIENCE, Instant.now().plusSeconds(300), Instant.now().minusSeconds(30));
    }
    private static String claimsToken(RSAKey key, String issuer, String subject, UUID tenant) throws Exception {
        var claims = new JWTClaimsSet.Builder().issuer(issuer).subject(subject).audience(AUDIENCE)
                .issueTime(Date.from(Instant.now().minusSeconds(30))).notBeforeTime(Date.from(Instant.now().minusSeconds(30)))
                .expirationTime(Date.from(Instant.now().plusSeconds(300)))
                .claim("tenant_id", tenant.toString()).claim("tenants", List.of(tenant.toString()))
                .claim("roles", List.of("TENANT_ADMIN")).claim("groups", List.of("orderhub-" + tenant))
                .claim("scope", "tenants:read orderhub:admin")
                .claim("permissions", List.of("TENANT_MEMBERS_MANAGE", "CATALOG_VIEW"))
                .claim("authorities", List.of("ROLE_ADMIN", "TENANT_" + tenant)).build();
        var jwt = new SignedJWT(new JWSHeader.Builder(JWSAlgorithm.RS256).type(JOSEObjectType.JWT).keyID(key.getKeyID()).build(), claims);
        jwt.sign(new RSASSASigner(key)); return jwt.serialize();
    }
    private static RSAKey key(String id) {
        try { return RealJwtTestSupport.generateRsaKey(id); }
        catch (Exception exception) { throw new ExceptionInInitializerError(exception); }
    }
    private static HttpServer server() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            for (var entry : java.util.Map.of("/jwks", KEY, "/second-jwks", SECOND_KEY).entrySet()) {
                server.createContext(entry.getKey(), exchange -> {
                    var body = new JWKSet(entry.getValue().toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
                    exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, body.length);
                    try (var output = exchange.getResponseBody()) { output.write(body); }
                });
            }
            server.start(); return server;
        } catch (Exception exception) { throw new ExceptionInInitializerError(exception); }
    }
}

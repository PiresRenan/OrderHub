package io.github.piresrenan.orderhub.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.*;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Instant;
import java.util.UUID;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.JWKSet;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.jdbc.core.JdbcTemplate;
import io.github.piresrenan.orderhub.security.support.RealJwtTestSupport;
import io.github.piresrenan.orderhub.support.PostgreSqlTestConfiguration;
import io.github.piresrenan.orderhub.users.application.port.in.*;
import io.github.piresrenan.orderhub.workforce.application.port.in.ColdStartStaffProvisioningUseCase;
import io.github.piresrenan.orderhub.workforce.application.model.StaffProvisioningIssuance;

/**
 * Why: unbound onboarding must preserve normal JWT trust and business proof requirements.
 * Covers: real configured Nimbus/JWK verification, HTTP policy/privacy and PostgreSQL owner workflows.
 * Prevents: claim-based authority, account takeover, spent proofs on negotiation errors and partial commits.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgreSqlTestConfiguration.class)
@org.junit.jupiter.api.extension.ExtendWith(org.springframework.boot.test.system.OutputCaptureExtension.class)
class SecurityRealJwtIdentityLifecycleAcceptanceTest {
    private static final String ISSUER = "https://synthetic-lifecycle-jwt.test";
    private static final String AUDIENCE = "orderhub-api";
    private static final RSAKey KEY = key("lifecycle-test-key");
    private static final String SECOND_ISSUER = "https://synthetic-second-lifecycle-jwt.test";
    private static final RSAKey SECOND_KEY = key("second-lifecycle-test-key");
    private static final HttpServer JWK_SERVER = server();
    @Autowired private MockMvc mvc;
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ResolveOrCreateExternalUserUseCase users;
    @Autowired private ResolveExternalIdentityUseCase identities;
    @Autowired private ColdStartStaffProvisioningUseCase cold;
    @Autowired private ExternalIdentityLifecycleUseCase links;

    @DynamicPropertySource static void properties(DynamicPropertyRegistry registry) {
        registry.add("orderhub.security.jwt.issuer", () -> ISSUER);
        registry.add("orderhub.security.jwt.audience", () -> AUDIENCE);
        registry.add("orderhub.security.jwt.jwk-set-uri", () -> "http://127.0.0.1:" + JWK_SERVER.getAddress().getPort() + "/jwks");
        registry.add("orderhub.security.jwt.additional-issuers[0].issuer", () -> SECOND_ISSUER);
        registry.add("orderhub.security.jwt.additional-issuers[0].jwk-set-uri", () -> "http://127.0.0.1:" + JWK_SERVER.getAddress().getPort() + "/second-jwks");
    }
    @AfterAll static void close() { JWK_SERVER.stop(0); }

    @Test void unboundVerifiedInviteeConsumesBusinessProofThroughBootstrap() throws Exception {
        var subject = UUID.randomUUID().toString(); var credential = coldProof();
        mvc.perform(post("/identity/bootstrap/staff").header("Authorization", bearer(subject))
                .contentType("application/json").content("{\"credential\":\"" + credential + "\"}"))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store"));
        assertThat(identities.resolve(new ResolveExternalIdentityQuery(ISSUER, subject))).isPresent();
    }

    @Test void newVerifiedIdentityConsumesLinkProofAndKeepsInternalUser() throws Exception {
        var oldSubject = UUID.randomUUID().toString(); var nextSubject = UUID.randomUUID().toString();
        var user = users.resolveOrCreate(new ResolveExternalIdentityQuery(ISSUER, oldSubject)).userId();
        var proof = (ExternalIdentityLinkIssuance.Issued) links.issue(user, UUID.randomUUID(), UUID.randomUUID());
        mvc.perform(post("/identity/bootstrap/external-links").header("Authorization", bearer(nextSubject))
                .contentType("application/json").content("{\"credential\":\"" + proof.credential() + "\"}"))
                .andExpect(status().isOk());
        assertThat(identities.resolve(new ResolveExternalIdentityQuery(ISSUER, nextSubject)).orElseThrow().userId()).isEqualTo(user);
    }

    @Test void boundOwnerCanIssueReplayAndCancelPrivateLinkProofThroughHttp() throws Exception {
        var subject = UUID.randomUUID().toString(); users.resolveOrCreate(new ResolveExternalIdentityQuery(ISSUER, subject));
        var body = "{\"operationId\":\"" + UUID.randomUUID() + "\",\"correlationId\":\"" + UUID.randomUUID() + "\"}";
        var response = mvc.perform(post("/identity/external-link-proofs").header("Authorization", bearer(subject)).contentType("application/json").content(body))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store")).andExpect(jsonPath("$.credential").isString()).andReturn().getResponse().getContentAsString();
        String proof = com.jayway.jsonpath.JsonPath.read(response, "$.proofId");
        mvc.perform(post("/identity/external-link-proofs").header("Authorization", bearer(subject)).contentType("application/json").content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.credential").doesNotExist());
        mvc.perform(delete("/identity/external-link-proofs/" + proof).header("Authorization", bearer(subject)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.changed").value(true));
    }

    @Test void invalidSignedTokensNeverConsumeProofAndReturnSanitizedAuthenticationProblems() throws Exception {
        var proof = coldProof(); var subject = UUID.randomUUID().toString(); var now = Instant.now();
        var invalid = java.util.List.of(
                RealJwtTestSupport.signedToken(key("untrusted-key"), ISSUER, subject, AUDIENCE, now.plusSeconds(300), now.minusSeconds(30)),
                RealJwtTestSupport.signedToken(KEY, "https://untrusted-issuer.test", subject, AUDIENCE, now.plusSeconds(300), now.minusSeconds(30)),
                RealJwtTestSupport.signedToken(KEY, ISSUER, subject, "other-audience", now.plusSeconds(300), now.minusSeconds(30)),
                RealJwtTestSupport.signedToken(KEY, ISSUER, subject, AUDIENCE, now.minusSeconds(300), now.minusSeconds(600)),
                RealJwtTestSupport.signedToken(KEY, ISSUER, subject, AUDIENCE, now.plusSeconds(600), now.plusSeconds(300)),
                RealJwtTestSupport.signedToken(KEY, null, subject, AUDIENCE, now.plusSeconds(300), now.minusSeconds(30)),
                RealJwtTestSupport.signedToken(KEY, ISSUER, subject, null, now.plusSeconds(300), now.minusSeconds(30)),
                RealJwtTestSupport.signedToken(KEY, ISSUER, null, AUDIENCE, now.plusSeconds(300), now.minusSeconds(30)));
        for (var token : invalid) {
            var response = mvc.perform(post("/identity/bootstrap/staff").header("Authorization", "Bearer " + token)
                    .contentType("application/json").content("{\"credential\":\"" + proof + "\"}"))
                    .andExpect(status().isUnauthorized()).andExpect(content().contentTypeCompatibleWith("application/problem+json")).andReturn().getResponse().getContentAsString();
            assertThat(response.contains(token) || response.contains(proof) || response.contains(subject)).isFalse();
        }
        assertThat(identities.resolve(new ResolveExternalIdentityQuery(ISSUER, subject))).isEmpty();
        mvc.perform(post("/identity/bootstrap/staff").header("Authorization", bearer(subject)).contentType("application/json").content("{\"credential\":\"" + proof + "\"}"))
                .andExpect(status().isOk());
    }

    @Test void staffIssuedRepresentationDoesNotPrintCredential() {
        var credential = "Synthetic-only-test-credential";
        var issued = new StaffProvisioningIssuance.Issued(UUID.randomUUID(), credential, java.time.OffsetDateTime.now().plusMinutes(5));
        assertThat(issued.toString().contains(credential)).isFalse();
    }

    @Test void httpColdStartCustomerLinkAndMembershipTransitionsPreserveOwnership() throws Exception {
        var fixture = httpFixture(); var customer = UUID.randomUUID(); var subject = UUID.randomUUID().toString();
        var customerUser = users.resolveOrCreate(new ResolveExternalIdentityQuery(ISSUER, subject)).userId();
        jdbc.update("INSERT INTO customers.customer_profiles VALUES (?, ?)", fixture.tenant(), customer);
        var issuance = mvc.perform(post("/administration/tenants/" + fixture.tenant() + "/customers/" + customer + "/account-link-proofs")
                .header("Authorization", bearer(fixture.manager())).contentType("application/json").content(operation()))
                .andExpect(status().isOk()).andExpect(header().string("Cache-Control", "no-store")).andReturn().getResponse().getContentAsString();
        String proof = com.jayway.jsonpath.JsonPath.read(issuance, "$.credential");
        mvc.perform(post("/tenants/" + fixture.tenant() + "/customer-account-links").header("Authorization", bearer(subject)).contentType("application/json").content(proofBody(proof)))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(customer.toString()));
        for (var action : new String[]{"suspend", "recover", "terminate"}) {
            mvc.perform(post("/administration/tenants/" + fixture.tenant() + "/memberships/" + customerUser + "/" + action).header("Authorization", bearer(fixture.manager())))
                    .andExpect(status().isOk()).andExpect(jsonPath("$.changed").value(true));
        }
        mvc.perform(post("/administration/tenants/" + fixture.tenant() + "/memberships/" + customerUser + "/recover").header("Authorization", bearer(fixture.manager())))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.instance").value("/identity-lifecycle"));
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customers.customer_account_bindings WHERE tenant_id = ? AND user_id = ?", Integer.class, fixture.tenant(), customerUser)).isEqualTo(1);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workforce.staff_profiles WHERE user_id = ?", Integer.class, customerUser)).isZero();
    }

    @Test void unauthorizedNormalStaffIssuanceAndRevokedColdProofArePolicyFailures() throws Exception {
        var fixture = httpFixture(); var stranger = UUID.randomUUID().toString();
        users.resolveOrCreate(new ResolveExternalIdentityQuery(ISSUER, stranger));
        var placement = jdbc.queryForMap("SELECT department_id, position_id FROM workforce.staff_placements WHERE tenant_id = ?", fixture.tenant());
        var body = "{\"departmentId\":\"" + placement.get("department_id") + "\",\"positionId\":\"" + placement.get("position_id") + "\",\"operationId\":\"" + UUID.randomUUID() + "\",\"correlationId\":\"" + UUID.randomUUID() + "\"}";
        mvc.perform(post("/administration/tenants/" + fixture.tenant() + "/staff-provisioning").header("Authorization", bearer(stranger)).contentType("application/json").content(body))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("identity-lifecycle-unavailable"));
        // A bound Platform actor is not normal Tenant Staff after the first-Staff ceremony.
        mvc.perform(post("/administration/tenants/" + fixture.tenant() + "/staff-provisioning").header("Authorization", bearer(fixture.platform())).contentType("application/json").content(body))
                .andExpect(status().isForbidden());
        var proof = coldProof();
        var digest = java.security.MessageDigest.getInstance("SHA-256").digest(java.util.Base64.getUrlDecoder().decode(proof));
        jdbc.update("DELETE FROM access_control.administrative_grants WHERE user_id = (SELECT issued_by_user_id FROM workforce.staff_provisioning_intents WHERE secret_digest = ?)", digest);
        mvc.perform(post("/identity/bootstrap/staff").header("Authorization", bearer(UUID.randomUUID().toString())).contentType("application/json").content(proofBody(proof)))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("identity-lifecycle-unavailable"));
    }

    @Test void normalStaffProofCanBeIssuedReplayedCancelledAndCannotThenBootstrap() throws Exception {
        var fixture = httpFixture(); var placement = jdbc.queryForMap("SELECT department_id, position_id FROM workforce.staff_placements WHERE tenant_id = ?", fixture.tenant());
        var body = "{\"departmentId\":\"" + placement.get("department_id") + "\",\"positionId\":\"" + placement.get("position_id") + "\",\"operationId\":\"" + UUID.randomUUID() + "\",\"correlationId\":\"" + UUID.randomUUID() + "\"}";
        var endpoint = "/administration/tenants/" + fixture.tenant() + "/staff-provisioning";
        var response = mvc.perform(post(endpoint).header("Authorization", bearer(fixture.manager())).contentType("application/json").content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String proof = com.jayway.jsonpath.JsonPath.read(response, "$.credential"); String intent = com.jayway.jsonpath.JsonPath.read(response, "$.intentId");
        mvc.perform(post(endpoint).header("Authorization", bearer(fixture.manager())).contentType("application/json").content(body))
                .andExpect(status().isOk()).andExpect(jsonPath("$.credential").doesNotExist());
        mvc.perform(delete(endpoint + "/" + intent).header("Authorization", bearer(fixture.manager()))).andExpect(status().isOk());
        mvc.perform(post("/identity/bootstrap/staff").header("Authorization", bearer(UUID.randomUUID().toString())).contentType("application/json").content(proofBody(proof)))
                .andExpect(status().isForbidden());
    }

    @Test void proofFailuresAreEquivalentAndJwtAloneCreatesNoUser() throws Exception {
        var subject = UUID.randomUUID().toString(); var proof = coldProof();
        var unknown = java.util.Base64.getUrlEncoder().withoutPadding().encodeToString(new byte[32]);
        var unknownResponse = mvc.perform(post("/identity/bootstrap/staff").header("Authorization", bearer(subject)).contentType("application/json").content(proofBody(unknown)))
                .andExpect(status().isForbidden()).andReturn().getResponse().getContentAsString();
        assertThat(identities.resolve(new ResolveExternalIdentityQuery(ISSUER, subject))).isEmpty();
        mvc.perform(get("/identity/external-accounts").header("Authorization", bearer(subject))).andExpect(status().isUnauthorized());
        mvc.perform(post("/identity/bootstrap/staff").header("Authorization", bearer(subject)).contentType("application/json").content(proofBody(proof))).andExpect(status().isOk());
        var replay = mvc.perform(post("/identity/bootstrap/staff").header("Authorization", bearer(subject)).contentType("application/json").content(proofBody(proof)))
                .andExpect(status().isForbidden()).andReturn().getResponse().getContentAsString();
        assertThat(replay).isEqualTo(unknownResponse);
    }

    @Test void privateAccountsSupportSafeMigrationAndLastPathDenial() throws Exception {
        var oldSubject = UUID.randomUUID().toString(); var nextSubject = UUID.randomUUID().toString();
        var user = users.resolveOrCreate(new ResolveExternalIdentityQuery(ISSUER, oldSubject)).userId();
        var proof = (ExternalIdentityLinkIssuance.Issued) links.issue(user, UUID.randomUUID(), UUID.randomUUID());
        mvc.perform(post("/identity/bootstrap/external-links").header("Authorization", bearer(nextSubject)).contentType("application/json").content(proofBody(proof.credential()))).andExpect(status().isOk());
        var oldBinding = jdbc.queryForObject("SELECT binding_id FROM users.external_identity_bindings WHERE issuer = ? AND subject = ?", UUID.class, ISSUER, oldSubject);
        mvc.perform(delete("/identity/external-accounts/" + oldBinding).header("Authorization", bearer(nextSubject))).andExpect(status().isOk());
        mvc.perform(get("/identity/external-accounts").header("Authorization", bearer(oldSubject))).andExpect(status().isUnauthorized());
        mvc.perform(get("/identity/external-accounts").header("Authorization", bearer(nextSubject))).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1)).andExpect(jsonPath("$[0].subject").doesNotExist());
        var nextBinding = jdbc.queryForObject("SELECT binding_id FROM users.external_identity_bindings WHERE issuer = ? AND subject = ?", UUID.class, ISSUER, nextSubject);
        mvc.perform(delete("/identity/external-accounts/" + nextBinding).header("Authorization", bearer(nextSubject))).andExpect(status().isForbidden());
    }

    @Test void malformedHttpRequestsRetainTheirFrameworkStatus() throws Exception {
        var token = bearer(UUID.randomUUID().toString());
        mvc.perform(get("/identity/bootstrap/staff").header("Authorization", token)).andExpect(status().isMethodNotAllowed()).andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        mvc.perform(post("/identity/bootstrap/staff").header("Authorization", token).contentType("application/json").content("{"))
                .andExpect(status().isBadRequest()).andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        mvc.perform(post("/identity/bootstrap/staff").header("Authorization", token).contentType("text/plain").content("synthetic-invalid-body"))
                .andExpect(status().isUnsupportedMediaType()).andExpect(content().contentTypeCompatibleWith("application/problem+json"));
    }

    @Test void verifiedClaimsAndRequestFieldsCannotInventInternalAuthorityOrSelectAnotherIdentity() throws Exception {
        var subject = UUID.randomUUID().toString(); var fakeSubject = UUID.randomUUID().toString();
        var claims = new com.nimbusds.jwt.JWTClaimsSet.Builder().issuer(ISSUER).subject(subject).audience(AUDIENCE)
                .expirationTime(java.util.Date.from(Instant.now().plusSeconds(300)))
                .claim("user_id", UUID.randomUUID().toString()).claim("roles", java.util.List.of("PLATFORM_TENANTS_MANAGE", "TENANT_GOVERNANCE"))
                .claim("scope", "TENANT_MEMBERS_MANAGE TENANT_ROLES_ASSIGN").build();
        var jwt = new com.nimbusds.jwt.SignedJWT(new com.nimbusds.jose.JWSHeader.Builder(com.nimbusds.jose.JWSAlgorithm.RS256).keyID(KEY.getKeyID()).build(), claims);
        jwt.sign(new com.nimbusds.jose.crypto.RSASSASigner(KEY)); var token = "Bearer " + jwt.serialize();
        mvc.perform(get("/identity/external-accounts").header("Authorization", token)).andExpect(status().isUnauthorized());
        var proof = coldProof();
        mvc.perform(post("/identity/bootstrap/staff").header("Authorization", token).contentType("application/json")
                .content("{\"credential\":\"" + proof + "\",\"issuer\":\"https://fake.test\",\"subject\":\"" + fakeSubject + "\",\"userId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isBadRequest());
        assertThat(identities.resolve(new ResolveExternalIdentityQuery(ISSUER, subject))).isEmpty();
        mvc.perform(post("/identity/bootstrap/staff").header("Authorization", token).contentType("application/json").content(proofBody(proof)))
                .andExpect(status().isOk());
        assertThat(identities.resolve(new ResolveExternalIdentityQuery(ISSUER, subject))).isPresent();
        assertThat(identities.resolve(new ResolveExternalIdentityQuery("https://fake.test", fakeSubject))).isEmpty();
    }

    @Test void auditFailureReturnsTechnicalProblemAndDoesNotSpendExternalProof() throws Exception {
        var owner = users.resolveOrCreate(new ResolveExternalIdentityQuery(ISSUER, UUID.randomUUID().toString())).userId();
        var proof = (ExternalIdentityLinkIssuance.Issued) links.issue(owner, UUID.randomUUID(), UUID.randomUUID()); var subject = UUID.randomUUID().toString();
        jdbc.execute("ALTER TABLE users.external_identity_events ADD CONSTRAINT synthetic_http_audit_failure CHECK (action <> 'LINKED') NOT VALID");
        try {
            var response = mvc.perform(post("/identity/bootstrap/external-links").header("Authorization", bearer(subject)).contentType("application/json").content(proofBody(proof.credential())))
                    .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("identity-lifecycle-technical-failure")).andReturn().getResponse().getContentAsString();
            assertThat(response.contains(proof.credential()) || response.contains(subject) || response.contains("INSERT") || response.contains("synthetic_http_audit_failure")).isFalse();
            assertThat(identities.resolve(new ResolveExternalIdentityQuery(ISSUER, subject))).isEmpty();
        } finally { jdbc.execute("ALTER TABLE users.external_identity_events DROP CONSTRAINT synthetic_http_audit_failure"); }
        mvc.perform(post("/identity/bootstrap/external-links").header("Authorization", bearer(subject)).contentType("application/json").content(proofBody(proof.credential())))
                .andExpect(status().isOk());
    }

    @Test void existingStaffPlacementConflictIsBoundedConflictNotTechnicalFailure() throws Exception {
        var fixture = httpFixture(); var department = UUID.randomUUID();
        jdbc.update("INSERT INTO workforce.departments (department_id, tenant_id, code, name) VALUES (?, ?, ?, 'Synthetic alternate department')", department, fixture.tenant(), "ALT_" + department);
        var position = jdbc.queryForObject("SELECT position_id FROM workforce.staff_placements WHERE tenant_id = ?", UUID.class, fixture.tenant());
        var body = "{\"departmentId\":\"" + department + "\",\"positionId\":\"" + position + "\",\"operationId\":\"" + UUID.randomUUID() + "\",\"correlationId\":\"" + UUID.randomUUID() + "\"}";
        var issued = mvc.perform(post("/administration/tenants/" + fixture.tenant() + "/staff-provisioning").header("Authorization", bearer(fixture.manager())).contentType("application/json").content(body))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String proof = com.jayway.jsonpath.JsonPath.read(issued, "$.credential");
        mvc.perform(post("/identity/bootstrap/staff").header("Authorization", bearer(fixture.manager())).contentType("application/json").content(proofBody(proof)))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("identity-lifecycle-conflict"));
    }

    @Test void unsupportedResponseFormatCannotSpendOneTimeProof() throws Exception {
        var proof = coldProof(); var subject = UUID.randomUUID().toString();
        mvc.perform(post("/identity/bootstrap/staff").header("Authorization", bearer(subject)).contentType("application/json").accept("application/xml").content(proofBody(proof)))
                .andExpect(status().isNotAcceptable());
        assertThat(identities.resolve(new ResolveExternalIdentityQuery(ISSUER, subject))).isEmpty();
        mvc.perform(post("/identity/bootstrap/staff").header("Authorization", bearer(subject)).contentType("application/json").content(proofBody(proof)))
                .andExpect(status().isOk());
    }

    @Test void bootstrapAuthenticationRetainsNoBearerOrAuthority() {
        var jwt = org.springframework.security.oauth2.jwt.Jwt.withTokenValue("synthetic-not-a-signed-token")
                .header("alg", "RS256").issuer(ISSUER).subject("synthetic-subject")
                .claim("scope", "TENANT_MEMBERS_MANAGE").claim("roles", java.util.List.of("TENANT_GOVERNANCE")).build();
        var authentication = new io.github.piresrenan.orderhub.security.adapter.in.authentication.jwt.VerifiedExternalIdentityJwtAuthenticationConverter().convert(jwt);
        assertThat(authentication.getAuthorities()).isEmpty(); assertThat(authentication.getCredentials()).isNull();
        assertThat(authentication.getPrincipal()).isInstanceOf(io.github.piresrenan.orderhub.security.application.model.VerifiedExternalIdentity.class);
        assertThat(authentication.toString().contains("synthetic-subject") || authentication.toString().contains(jwt.getTokenValue())).isFalse();
    }

    @Test void missingBearerHasSanitizedProblemAndSuccessfulConsumptionDoesNotLogCredentials(org.springframework.boot.test.system.CapturedOutput output) throws Exception {
        mvc.perform(post("/identity/bootstrap/staff").contentType("application/json").content(proofBody("synthetic-invalid-proof")))
                .andExpect(status().isUnauthorized()).andExpect(content().contentTypeCompatibleWith("application/problem+json"));
        var subject = UUID.randomUUID().toString(); var proof = coldProof(); var token = bearer(subject);
        mvc.perform(post("/identity/bootstrap/staff").header("Authorization", token).contentType("application/json").content(proofBody(proof)))
                .andExpect(status().isOk());
        assertThat(output.getAll().contains(proof) || output.getAll().contains(token) || output.getAll().contains(subject)).isFalse();
    }

    @Test void independentlyVerifiedSecondProviderMigratesTheSameUserThroughHttp() throws Exception {
        var oldSubject = UUID.randomUUID().toString(); var nextSubject = UUID.randomUUID().toString();
        var owner = users.resolveOrCreate(new ResolveExternalIdentityQuery(ISSUER, oldSubject)).userId();
        var proof = (ExternalIdentityLinkIssuance.Issued) links.issue(owner, UUID.randomUUID(), UUID.randomUUID());
        var token = "Bearer " + RealJwtTestSupport.signedToken(SECOND_KEY, SECOND_ISSUER, nextSubject, AUDIENCE, Instant.now().plusSeconds(300), Instant.now().minusSeconds(30));
        mvc.perform(post("/identity/bootstrap/external-links").header("Authorization", token).contentType("application/json").content(proofBody(proof.credential())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.id").value(owner.toString()));
        var binding = jdbc.queryForObject("SELECT binding_id FROM users.external_identity_bindings WHERE issuer = ? AND subject = ?", UUID.class, ISSUER, oldSubject);
        mvc.perform(delete("/identity/external-accounts/" + binding).header("Authorization", token)).andExpect(status().isOk());
        mvc.perform(get("/identity/external-accounts").header("Authorization", token)).andExpect(status().isOk()).andExpect(jsonPath("$[0].issuer").value(SECOND_ISSUER));
        assertThat(identities.resolve(new ResolveExternalIdentityQuery(SECOND_ISSUER, nextSubject)).orElseThrow().userId()).isEqualTo(owner);
    }

    @Test void customerProofCancellationPreventsClaimAndUnknownCustomerIsNotExposedToOutsider() throws Exception {
        var fixture = httpFixture(); var customer = UUID.randomUUID(); var outsider = UUID.randomUUID().toString();
        users.resolveOrCreate(new ResolveExternalIdentityQuery(ISSUER, outsider));
        jdbc.update("INSERT INTO customers.customer_profiles VALUES (?, ?)", fixture.tenant(), customer);
        String previous = null;
        for (var selected : new UUID[]{customer, UUID.randomUUID()}) {
            var response = mvc.perform(post("/administration/tenants/" + fixture.tenant() + "/customers/" + selected + "/account-link-proofs")
                    .header("Authorization", bearer(outsider)).contentType("application/json").content(operation())).andExpect(status().isForbidden()).andReturn().getResponse().getContentAsString();
            if (previous != null) { assertThat(response).isEqualTo(previous); } previous = response;
        }
        var issued = mvc.perform(post("/administration/tenants/" + fixture.tenant() + "/customers/" + customer + "/account-link-proofs")
                .header("Authorization", bearer(fixture.manager())).contentType("application/json").content(operation())).andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String proof = com.jayway.jsonpath.JsonPath.read(issued, "$.credential"); String id = com.jayway.jsonpath.JsonPath.read(issued, "$.proofId");
        mvc.perform(delete("/administration/tenants/" + fixture.tenant() + "/customer-account-link-proofs/" + id).header("Authorization", bearer(fixture.manager())))
                .andExpect(status().isOk()).andExpect(jsonPath("$.changed").value(true));
        mvc.perform(post("/tenants/" + fixture.tenant() + "/customer-account-links").header("Authorization", bearer(outsider)).contentType("application/json").content(proofBody(proof)))
                .andExpect(status().isForbidden());
    }

    private HttpFixture httpFixture() throws Exception {
        var platform = UUID.randomUUID().toString(); var actor = users.resolveOrCreate(new ResolveExternalIdentityQuery(ISSUER, platform)).userId(); var tenant = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants.tenants (id, name, status) VALUES (?, 'Synthetic HTTP lifecycle', 'ACTIVE')", tenant);
        jdbc.update("INSERT INTO access_control.administrative_grants (grant_id, user_id, scope_type, scope_id, permission_code) VALUES (?, ?, 'PLATFORM', NULL, 'PLATFORM_TENANTS_MANAGE')", UUID.randomUUID(), actor);
        var issuance = mvc.perform(post("/administration/tenants/" + tenant + "/initial-staff-provisioning").header("Authorization", bearer(platform)).contentType("application/json").content(operation()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
        String proof = com.jayway.jsonpath.JsonPath.read(issuance, "$.credential"); var manager = UUID.randomUUID().toString();
        mvc.perform(post("/identity/bootstrap/staff").header("Authorization", bearer(manager)).contentType("application/json").content(proofBody(proof))).andExpect(status().isOk());
        return new HttpFixture(tenant, manager, platform);
    }
    private static String operation() { return "{\"operationId\":\"" + UUID.randomUUID() + "\",\"correlationId\":\"" + UUID.randomUUID() + "\"}"; }
    private static String proofBody(String proof) { return "{\"credential\":\"" + proof + "\"}"; }
    private record HttpFixture(UUID tenant, String manager, String platform) {}

    private String coldProof() {
        var actor = users.resolveOrCreate(new ResolveExternalIdentityQuery(ISSUER, UUID.randomUUID().toString())).userId();
        var tenant = UUID.randomUUID();
        jdbc.update("INSERT INTO tenants.tenants (id, name, status) VALUES (?, 'Synthetic JWT lifecycle', 'ACTIVE')", tenant);
        jdbc.update("INSERT INTO access_control.administrative_grants (grant_id, user_id, scope_type, scope_id, permission_code) VALUES (?, ?, 'PLATFORM', NULL, 'PLATFORM_TENANTS_MANAGE')", UUID.randomUUID(), actor);
        return ((StaffProvisioningIssuance.Issued) cold.issue(actor, tenant, UUID.randomUUID(), UUID.randomUUID())).credential();
    }
    private String bearer(String subject) throws Exception { return "Bearer " + RealJwtTestSupport.signedToken(KEY, ISSUER, subject, AUDIENCE, Instant.now().plusSeconds(300), Instant.now().minusSeconds(30)); }
    private static RSAKey key(String id) {
        try { return RealJwtTestSupport.generateRsaKey(id); }
        catch (Exception exception) { throw new ExceptionInInitializerError(exception); }
    }
    private static HttpServer server() {
        try {
            var server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
            server.createContext("/jwks", exchange -> {
                var body = new JWKSet(KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json");
                exchange.sendResponseHeaders(200, body.length);
                try (var output = exchange.getResponseBody()) { output.write(body); }
            });
            server.createContext("/second-jwks", exchange -> {
                var body = new JWKSet(SECOND_KEY.toPublicJWK()).toString().getBytes(StandardCharsets.UTF_8);
                exchange.getResponseHeaders().set("Content-Type", "application/json"); exchange.sendResponseHeaders(200, body.length);
                try (var output = exchange.getResponseBody()) { output.write(body); }
            });
            server.start(); return server;
        } catch (Exception exception) { throw new ExceptionInInitializerError(exception); }
    }
}

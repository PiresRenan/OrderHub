package io.github.piresrenan.orderhub.bootstrap;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.Arrays;
import java.util.TreeSet;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.web.servlet.mvc.method.annotation.RequestMappingHandlerMapping;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.RSAKey;

import io.github.piresrenan.orderhub.authorization.application.port.in.administration.AuthorizeAdministrativeActionUseCase;
import io.github.piresrenan.orderhub.authorization.domain.model.AdministrativeGrant;
import io.github.piresrenan.orderhub.authorization.domain.model.AdministrativeScope;
import io.github.piresrenan.orderhub.authorization.domain.model.AuthorizationDecision;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionCode;
import io.github.piresrenan.orderhub.bootstrap.application.port.in.BootstrapFirstOperatorUseCase;
import io.github.piresrenan.orderhub.bootstrap.application.port.in.FirstOperatorBootstrapOutcome;
import io.github.piresrenan.orderhub.bootstrap.application.port.in.FirstOperatorBootstrapRequest;
import io.github.piresrenan.orderhub.security.support.RealJwtTestSupport;
import io.github.piresrenan.orderhub.support.PostgreSqlTestConfiguration;

/**
 * Why: the bootstrapped operator must enter normal OrderHub only through normal authentication and authorization.
 * Scenario: a normal web context over real PostgreSQL and real signed JWTs; the ceremony use case the offline
 * command invokes runs once, then every step uses public HTTP routes.
 * Covers: web startup isolation, no bootstrap route, unbound identity rejection, exact bearer-to-User
 * resolution, exact Platform authority, absent unrelated Platform and Tenant authority, and the existing
 * cold-start first-Staff ceremony.
 * Prevents: a bootstrap principal or bypass, a startup seed and a Platform operator mistaken for Tenant Staff.
 */
@SpringBootTest(properties = {
        "orderhub.security.jwt.token-profile=GENERIC",
        "orderhub.security.jwt.issuer=" + FirstOperatorBootstrapJourneyTest.ISSUER,
        "orderhub.security.jwt.audience=" + FirstOperatorBootstrapJourneyTest.AUDIENCE,
        "orderhub.security.jwt.jwk-set-uri=http://127.0.0.1:1/unused-first-operator-jwks"
})
@AutoConfigureMockMvc
@Import({PostgreSqlTestConfiguration.class, FirstOperatorBootstrapJourneyTest.JwtConfiguration.class})
class FirstOperatorBootstrapJourneyTest {

    static final String ISSUER = "https://identity.journey.test";
    static final String AUDIENCE = "orderhub-api";
    private static final String SUBJECT = "journey-first-operator-subject";
    private static final RSAKey KEY = RealJwtTestSupport.generateRsaKey("first-operator-journey-key");

    @Autowired MockMvc mvc;
    @Autowired JdbcTemplate jdbc;
    @Autowired BootstrapFirstOperatorUseCase bootstrap;
    @Autowired AuthorizeAdministrativeActionUseCase administration;
    @Autowired @Qualifier("requestMappingHandlerMapping") RequestMappingHandlerMapping mappings;

    @Test
    void bootstrappedOperatorUsesOnlyNormalAuthenticationAuthorizationAndFirstStaffCeremony() throws Exception {
        // Normal web startup neither ran the ceremony nor exposes it.
        assertThat(jdbc.queryForObject("SELECT state FROM bootstrap.first_operator_ceremony", String.class)).isEqualTo("OPEN");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM bootstrap.first_operator_ceremony_events", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users.users", Long.class)).isZero();
        var bootstrapRoutes = new TreeSet<String>();
        mappings.getHandlerMethods().keySet().forEach(mapping -> mapping.getPatternValues().stream()
                .filter(path -> path.toLowerCase().contains("bootstrap") || path.toLowerCase().contains("first-operator"))
                .forEach(bootstrapRoutes::add));
        assertThat(bootstrapRoutes).containsExactly("/identity/bootstrap/external-links", "/identity/bootstrap/staff");

        // RED-5 stays true: a valid token for an unbound identity is not an OrderHub User.
        mvc.perform(get("/tenants").header(HttpHeaders.AUTHORIZATION, bearer())).andExpect(status().isUnauthorized());

        assertThat(bootstrap.bootstrap(new FirstOperatorBootstrapRequest(ISSUER, SUBJECT, UUID.randomUUID())))
                .isEqualTo(FirstOperatorBootstrapOutcome.COMPLETED);
        var operator = jdbc.queryForObject("SELECT operator_user_id FROM bootstrap.first_operator_ceremony", UUID.class);

        // Exact Platform authority, and nothing else.
        for (var permission : Arrays.stream(PermissionCode.values()).filter(p -> p.supportsAdministrativeScope(
                io.github.piresrenan.orderhub.authorization.domain.model.AdministrativeScopeType.PLATFORM)).toList()) {
            assertThat(administration.authorize(new AdministrativeGrant(operator, AdministrativeScope.platform(), permission)))
                    .as(permission.name())
                    .isEqualTo(permission == PermissionCode.PLATFORM_TENANTS_MANAGE ? AuthorizationDecision.ALLOW : AuthorizationDecision.DENY);
        }
        mvc.perform(get("/platform/organizations").header(HttpHeaders.AUTHORIZATION, bearer())).andExpect(status().isForbidden());
        mvc.perform(post("/platform/organizations").header(HttpHeaders.AUTHORIZATION, bearer())
                .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"Not allowed\"}")).andExpect(status().isForbidden());

        // Not Tenant Staff: no selectable Tenant, no membership, no role.
        mvc.perform(get("/tenants").header(HttpHeaders.AUTHORIZATION, bearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.items").isEmpty());

        // Normal Platform administration, then the existing cold-start first-Staff ceremony.
        var created = mvc.perform(post("/platform/tenants").header(HttpHeaders.AUTHORIZATION, bearer())
                        .contentType(MediaType.APPLICATION_JSON).content("{\"name\":\"First retained Tenant\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
        var tenant = UUID.fromString(created.replaceAll(".*\"id\"\\s*:\\s*\"([0-9a-f-]{36})\".*", "$1"));
        mvc.perform(post("/administration/tenants/{tenantId}/initial-staff-provisioning", tenant)
                        .header(HttpHeaders.AUTHORIZATION, bearer()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"operationId\":\"" + UUID.randomUUID() + "\",\"correlationId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.credential").isNotEmpty());
        mvc.perform(get("/catalog/products").header(HttpHeaders.AUTHORIZATION, bearer())
                .header("X-Tenant-Id", tenant.toString())).andExpect(status().isForbidden());

        assertThat(jdbc.queryForObject("SELECT count(*) FROM users.tenant_memberships WHERE user_id = ?", Long.class, operator)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM access_control.role_assignments WHERE user_id = ?", Long.class, operator)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workforce.staff_profiles", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM customers.customer_profiles", Long.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM access_control.user_permission_overrides WHERE user_id = ?", Long.class, operator)).isZero();
        assertThat(jdbc.queryForList("SELECT permission_code FROM access_control.administrative_grants WHERE user_id = ?", String.class, operator))
                .containsExactly("PLATFORM_TENANTS_MANAGE");
        assertThat(jdbc.queryForObject("SELECT count(*) FROM users.users", Long.class)).isEqualTo(1);
    }

    private static String bearer() throws JOSEException {
        var now = Instant.now();
        return "Bearer " + RealJwtTestSupport.signedToken(KEY, ISSUER, SUBJECT, AUDIENCE, now.plusSeconds(300), now.minusSeconds(30));
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class JwtConfiguration {
        @Bean
        @Primary
        JwtDecoder firstOperatorJourneyJwtDecoder() throws JOSEException {
            return RealJwtTestSupport.decoder(KEY, ISSUER, AUDIENCE);
        }
    }
}

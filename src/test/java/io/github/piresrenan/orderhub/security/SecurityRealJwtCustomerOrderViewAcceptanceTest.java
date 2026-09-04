package io.github.piresrenan.orderhub.security;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
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
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import com.nimbusds.jose.JOSEException;
import com.nimbusds.jose.jwk.RSAKey;

import io.github.piresrenan.orderhub.orders.application.port.out.OrderRepository;
import io.github.piresrenan.orderhub.orders.domain.model.Order;
import io.github.piresrenan.orderhub.orders.domain.model.OrderItem;
import io.github.piresrenan.orderhub.security.support.RealJwtTestSupport;
import io.github.piresrenan.orderhub.support.PostgreSqlTestConfiguration;
import io.github.piresrenan.orderhub.users.application.port.in.FindTenantMembershipQuery;
import io.github.piresrenan.orderhub.users.application.port.in.FindTenantMembershipUseCase;
import io.github.piresrenan.orderhub.users.application.port.in.ResolveExternalIdentityQuery;
import io.github.piresrenan.orderhub.users.application.port.in.ResolveExternalIdentityUseCase;
import io.github.piresrenan.orderhub.users.application.port.in.ResolvedUserIdentity;
import io.github.piresrenan.orderhub.users.domain.model.TenantMembership;

/**
 * Vertical acceptance evidence for Customer own-Order reads.
 *
 * <p>
 * JWT verification, MVC dispatch, Orders persistence, Customer account-binding
 * persistence, Customer authorization and ViewCustomerOrderService remain
 * production implementations. Only external identity resolution and Tenant
 * membership lookup use established Security test seams.
 * </p>
 */
@SpringBootTest(properties = {
        "orderhub.security.jwt.issuer=https://issuer.example.test",
        "orderhub.security.jwt.audience=orderhub-api",
        "orderhub.security.jwt.jwk-set-uri=http://127.0.0.1:1/unused-customer-order-view-jwks"
})
@AutoConfigureMockMvc
@Import({
        PostgreSqlTestConfiguration.class,
        SecurityRealJwtCustomerOrderViewAcceptanceTest.RealJwtConfiguration.class
})
class SecurityRealJwtCustomerOrderViewAcceptanceTest {

    private static final String ISSUER =
            "https://issuer.example.test";

    private static final String AUDIENCE =
            "orderhub-api";

    private static final String SUBJECT =
            "synthetic-customer-order-view-subject";

    private static final RSAKey SIGNING_KEY =
            RealJwtTestSupport.generateRsaKey(
                    "orderhub-customer-order-view-key");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private OrderRepository orders;

    @MockitoBean
    private ResolveExternalIdentityUseCase externalIdentities;

    @MockitoBean
    private FindTenantMembershipUseCase memberships;

    @BeforeEach
    void cleanOwnedResourceState() {

        jdbcTemplate.update("""
                TRUNCATE TABLE
                    customers.customer_account_bindings,
                    customers.customer_profiles,
                    orders.order_items,
                    orders.orders
                """);
    }

    @Test
    void returnsOwnedOrderThroughRealJwtPersistenceBindingAndAuthorization()
            throws Exception {

        var userId =
                UUID.randomUUID();

        var tenantId =
                UUID.randomUUID();

        var customerId =
                UUID.randomUUID();

        var orderId =
                UUID.randomUUID();

        var variantId =
                UUID.randomUUID();

        allowIdentity(
                userId);

        allowMembership(
                userId,
                tenantId);

        seedCustomerAccountBinding(
                tenantId,
                customerId,
                userId);

        saveOrder(
                tenantId,
                customerId,
                orderId,
                variantId);

        mockMvc.perform(
                        get(
                                "/orders/{orderId}",
                                orderId)
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(
                                                validToken()))
                                .header(
                                        "X-Tenant-Id",
                                        tenantId))
                .andExpect(
                        status().isOk())
                .andExpect(
                        content().contentTypeCompatibleWith(
                                MediaType.APPLICATION_JSON))
                .andExpect(
                        jsonPath("$.id")
                                .value(
                                        orderId.toString()))
                .andExpect(
                        jsonPath("$.tenantId")
                                .value(
                                        tenantId.toString()))
                .andExpect(
                        jsonPath("$.customerId")
                                .value(
                                        customerId.toString()))
                .andExpect(
                        jsonPath("$.status")
                                .exists())
                .andExpect(
                        jsonPath("$.items.length()")
                                .value(
                                        1))
                .andExpect(
                        jsonPath("$.items[0].variantId")
                                .value(
                                        variantId.toString()))
                .andExpect(
                        jsonPath("$.items[0].quantity")
                                .value(
                                        2));
    }

    @Test
    void sameTenantOtherCustomerOrderIsIndistinguishableFromMissingOrder()
            throws Exception {

        var userId =
                UUID.randomUUID();

        var tenantId =
                UUID.randomUUID();

        var ownedCustomerId =
                UUID.randomUUID();

        var otherCustomerId =
                UUID.randomUUID();

        var orderId =
                UUID.randomUUID();

        allowIdentity(
                userId);

        allowMembership(
                userId,
                tenantId);

        seedCustomerAccountBinding(
                tenantId,
                ownedCustomerId,
                userId);

        seedCustomerProfile(
                tenantId,
                otherCustomerId);

        saveOrder(
                tenantId,
                otherCustomerId,
                orderId,
                UUID.randomUUID());

        var token =
                validToken();

        var inaccessibleResponse =
                performUnavailable(
                        tenantId,
                        orderId,
                        token);

        deleteOrder(
                tenantId,
                orderId);

        var missingResponse =
                performUnavailable(
                        tenantId,
                        orderId,
                        token);

        assertThat(inaccessibleResponse)
                .as(
                        "same-Tenant foreign Customer Order and missing Order "
                                + "must expose the same HTTP representation")
                .isEqualTo(
                        missingResponse);

        assertThat(inaccessibleResponse)
                .doesNotContain(
                        ownedCustomerId.toString(),
                        otherCustomerId.toString(),
                        userId.toString(),
                        tenantId.toString(),
                        "RESOURCE_OWNER",
                        "authorization",
                        "forbidden",
                        "denied");
    }

    @Test
    void changingOnlyTrustedTenantCannotReadOrderPersistedInAnotherTenant()
            throws Exception {

        var userId =
                UUID.randomUUID();

        var tenantA =
                UUID.randomUUID();

        var tenantB =
                UUID.randomUUID();

        var customerId =
                UUID.randomUUID();

        var orderId =
                UUID.randomUUID();

        allowIdentity(
                userId);

        allowMembership(
                userId,
                tenantA);

        allowMembership(
                userId,
                tenantB);

        /*
         * Same actor + same Customer id are valid in both Tenants.
         * Tenant-scoped Order lookup must therefore be the fact that prevents
         * Tenant B from reaching the Order persisted in Tenant A.
         */
        seedCustomerAccountBinding(
                tenantA,
                customerId,
                userId);

        seedCustomerAccountBinding(
                tenantB,
                customerId,
                userId);

        saveOrder(
                tenantA,
                customerId,
                orderId,
                UUID.randomUUID());

        var token =
                validToken();

        mockMvc.perform(
                        get(
                                "/orders/{orderId}",
                                orderId)
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(
                                                token))
                                .header(
                                        "X-Tenant-Id",
                                        tenantA))
                .andExpect(
                        status().isOk())
                .andExpect(
                        jsonPath("$.tenantId")
                                .value(
                                        tenantA.toString()))
                .andExpect(
                        jsonPath("$.customerId")
                                .value(
                                        customerId.toString()));

        var crossTenantResponse =
                performUnavailable(
                        tenantB,
                        orderId,
                        token);

        assertThat(crossTenantResponse)
                .doesNotContain(
                        tenantA.toString(),
                        tenantB.toString(),
                        customerId.toString(),
                        userId.toString(),
                        "RESOURCE_OWNER",
                        "authorization",
                        "forbidden",
                        "denied");
    }

    private String performUnavailable(
            UUID tenantId,
            UUID orderId,
            String token)
            throws Exception {

        return mockMvc.perform(
                        get(
                                "/orders/{orderId}",
                                orderId)
                                .header(
                                        HttpHeaders.AUTHORIZATION,
                                        bearer(
                                                token))
                                .header(
                                        "X-Tenant-Id",
                                        tenantId))
                .andExpect(
                        status().isNotFound())
                .andExpect(
                        content().contentTypeCompatibleWith(
                                MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(
                        jsonPath("$.type")
                                .value(
                                        "urn:orderhub:problem:order-not-found"))
                .andExpect(
                        jsonPath("$.title")
                                .value(
                                        "Order not found"))
                .andExpect(
                        jsonPath("$.status")
                                .value(
                                        404))
                .andExpect(
                        jsonPath("$.detail")
                                .value(
                                        "The requested order could not be found."))
                .andExpect(
                        jsonPath("$.code")
                                .value(
                                        "ORDER_NOT_FOUND"))
                .andReturn()
                .getResponse()
                .getContentAsString();
    }

    private void allowIdentity(
            UUID userId) {

        when(externalIdentities.resolve(
                new ResolveExternalIdentityQuery(
                        ISSUER,
                        SUBJECT)))
                .thenReturn(
                        Optional.of(
                                new ResolvedUserIdentity(
                                        userId)));
    }

    private void allowMembership(
            UUID userId,
            UUID tenantId) {

        var membership =
                mock(
                        TenantMembership.class);

        doReturn(
                Optional.of(
                        membership))
                .when(
                        memberships)
                .find(
                        new FindTenantMembershipQuery(
                                userId,
                                tenantId));
    }

    private void seedCustomerProfile(
            UUID tenantId,
            UUID customerId) {

        jdbcTemplate.update("""
                INSERT INTO customers.customer_profiles (
                    tenant_id,
                    customer_id
                )
                VALUES (?, ?)
                ON CONFLICT (tenant_id, customer_id)
                DO NOTHING
                """,
                tenantId,
                customerId);
    }

    private void seedCustomerAccountBinding(
            UUID tenantId,
            UUID customerId,
            UUID userId) {

        seedCustomerProfile(
                tenantId,
                customerId);

        jdbcTemplate.update("""
                INSERT INTO customers.customer_account_bindings (
                    tenant_id,
                    customer_id,
                    user_id
                )
                VALUES (?, ?, ?)
                ON CONFLICT (tenant_id, customer_id, user_id)
                DO NOTHING
                """,
                tenantId,
                customerId,
                userId);
    }

    private void saveOrder(
            UUID tenantId,
            UUID customerId,
            UUID orderId,
            UUID variantId) {

        orders.save(
                Order.create(
                        orderId,
                        tenantId,
                        customerId,
                        List.of(
                                new OrderItem(
                                        variantId,
                                        2))));
    }

    private void deleteOrder(
            UUID tenantId,
            UUID orderId) {

        jdbcTemplate.update("""
                DELETE FROM orders.order_items
                WHERE tenant_id = ?
                  AND order_id = ?
                """,
                tenantId,
                orderId);

        jdbcTemplate.update("""
                DELETE FROM orders.orders
                WHERE tenant_id = ?
                  AND id = ?
                """,
                tenantId,
                orderId);
    }

    private static String validToken()
            throws JOSEException {

        var now =
                Instant.now();

        return RealJwtTestSupport.signedToken(
                SIGNING_KEY,
                ISSUER,
                SUBJECT,
                AUDIENCE,
                now.plusSeconds(
                        300),
                now.minusSeconds(
                        30));
    }

    private static String bearer(
            String token) {

        return "Bearer " + token;
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class RealJwtConfiguration {

        @Bean
        @Primary
        JwtDecoder realJwtDecoder()
                throws JOSEException {

            return RealJwtTestSupport.decoder(
                    SIGNING_KEY,
                    ISSUER,
                    AUDIENCE);
        }
    }
}

package io.github.piresrenan.orderhub.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.when;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import io.github.piresrenan.orderhub.security.adapter.in.authentication.AuthenticatedUserAuthenticationToken;
import io.github.piresrenan.orderhub.security.application.model.AuthenticatedUserPrincipal;
import io.github.piresrenan.orderhub.security.application.model.TrustedTenantContext;
import io.github.piresrenan.orderhub.security.application.port.in.ResolveTrustedTenantContextQuery;
import io.github.piresrenan.orderhub.security.application.port.in.ResolveTrustedTenantContextUseCase;
import io.github.piresrenan.orderhub.support.PostgreSqlTestConfiguration;

@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgreSqlTestConfiguration.class)
class CreateOrderIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @MockitoBean
    private ResolveTrustedTenantContextUseCase resolveTrustedTenantContextUseCase;

    /**
     * Ensures each full-stack scenario starts from an empty Orders business state
     * while preserving the schema already created by Flyway.
     */
    @BeforeEach
    void cleanPersistedOrders() {
        jdbcTemplate.update("""
                TRUNCATE TABLE
                    orders.order_items,
                    orders.orders
                """);
    }

    @Test
    void createsOrderThroughCompleteApplicationStack() throws Exception {
        // Why: a successful HTTP response is insufficient if the created aggregate is
        // not durably stored.
        // Covers: authenticated HTTP -> trusted Tenant boundary -> Orders controller
        // -> use case -> domain -> PostgreSQL repository, including persisted root and
        // owned item. JWT and membership resolution are covered by dedicated Security
        // end-to-end tests.
        // Prevents: false-positive integration success where the API returns 201 but
        // persistence wiring does not actually write the aggregate.

        var authenticatedUserId =
                UUID.fromString(
                        "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

        var tenantId =
                UUID.fromString(
                        "11111111-1111-1111-1111-111111111111");

        var customerId =
                UUID.fromString(
                        "22222222-2222-2222-2222-222222222222");

        var productId =
                UUID.fromString(
                        "33333333-3333-3333-3333-333333333333");

        var authenticatedPrincipal =
                new AuthenticatedUserPrincipal(
                        authenticatedUserId);

        var requestAuthentication =
                new AuthenticatedUserAuthenticationToken(
                        authenticatedPrincipal);

        when(resolveTrustedTenantContextUseCase.resolve(
                new ResolveTrustedTenantContextQuery(
                        authenticatedPrincipal,
                        tenantId)))
                .thenReturn(
                        Optional.of(
                                new TrustedTenantContext(
                                        tenantId)));

        mockMvc.perform(
                        post("/orders")
                                .with(
                                        authentication(
                                                requestAuthentication))
                                .header(
                                        "X-Tenant-Id",
                                        tenantId.toString())
                                .contentType(
                                        MediaType.APPLICATION_JSON)
                                .content("""
                                        {
                                          "customerId":
                                            "22222222-2222-2222-2222-222222222222",
                                          "items": [{
                                            "productId":
                                              "33333333-3333-3333-3333-333333333333",
                                            "quantity": 2
                                          }]
                                        }
                                        """))
                .andExpect(
                        status().isCreated())
                .andExpect(
                        jsonPath("$.id")
                                .isString())
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
                                .value(
                                        "CREATED"));

        var persistedOrderCount =
                jdbcTemplate.queryForObject("""
                        SELECT COUNT(*)
                        FROM orders.orders
                        WHERE tenant_id = ?
                          AND customer_id = ?
                          AND status = 'CREATED'
                        """,
                        Long.class,
                        tenantId,
                        customerId);

        var persistedItemCount =
                jdbcTemplate.queryForObject("""
                        SELECT COUNT(*)
                        FROM orders.order_items AS item
                        JOIN orders.orders AS root
                          ON root.tenant_id = item.tenant_id
                         AND root.id = item.order_id
                        WHERE root.tenant_id = ?
                          AND root.customer_id = ?
                          AND item.product_id = ?
                          AND item.quantity = ?
                        """,
                        Long.class,
                        tenantId,
                        customerId,
                        productId,
                        2);

        assertThat(persistedOrderCount)
                .isEqualTo(
                        1L);

        assertThat(persistedItemCount)
                .isEqualTo(
                        1L);
    }
}

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

    @BeforeEach
    void cleanBusinessState() {

        jdbcTemplate.update("""
                TRUNCATE TABLE
                    inventory.inventory_commitments,
                    inventory.inventory_positions,
                    inventory.tenant_policies,
                    catalog.media,
                    catalog.variant_base_prices,
                    catalog.product_variant_attributes,
                    catalog.product_categories,
                    catalog.product_variants,
                    catalog.categories,
                    catalog.category_hierarchy_guards,
                    catalog.products,
                    orders.order_items,
                    orders.orders
                """);
    }

    @Test
    void createsOrderThroughCompleteApplicationStackOnlyAfterInventoryCommitment()
            throws Exception {

        var authenticatedUserId =
                UUID.fromString(
                        "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

        var tenantId =
                UUID.fromString(
                        "11111111-1111-1111-1111-111111111111");

        var customerId =
                UUID.fromString(
                        "22222222-2222-2222-2222-222222222222");

        var variantId =
                UUID.fromString(
                        "33333333-3333-3333-3333-333333333333");

        seedActiveCatalogVariant(
                tenantId,
                variantId);

        jdbcTemplate.update("""
                INSERT INTO inventory.tenant_policies (
                    tenant_id,
                    policy
                )
                VALUES (?, 'DENY')
                """,
                tenantId);

        jdbcTemplate.update("""
                INSERT INTO inventory.inventory_positions (
                    tenant_id,
                    variant_id,
                    on_hand,
                    committed,
                    backordered,
                    safety_stock
                )
                VALUES (?, ?, 10, 0, 0, 0)
                """,
                tenantId,
                variantId);

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
                                            "variantId":
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
                                        "CREATED"))
                .andExpect(
                        jsonPath("$.allocationOutcome")
                                .value(
                                        "FULLY_ALLOCATED"))
                .andExpect(
                        jsonPath("$.items[0].variantId")
                                .value(
                                        variantId.toString()));

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
                          AND item.variant_id = ?
                          AND item.quantity = 2
                        """,
                        Long.class,
                        tenantId,
                        customerId,
                        variantId);

        var committed =
                jdbcTemplate.queryForObject("""
                        SELECT committed
                        FROM inventory.inventory_positions
                        WHERE tenant_id = ?
                          AND variant_id = ?
                        """,
                        Long.class,
                        tenantId,
                        variantId);

        var commitmentCount =
                jdbcTemplate.queryForObject("""
                        SELECT COUNT(*)
                        FROM inventory.inventory_commitments
                        WHERE tenant_id = ?
                          AND variant_id = ?
                          AND requested_quantity = 2
                          AND allocated_quantity = 2
                          AND backordered_quantity = 0
                        """,
                        Long.class,
                        tenantId,
                        variantId);

        assertThat(persistedOrderCount)
                .isEqualTo(1);

        assertThat(persistedItemCount)
                .isEqualTo(1);

        assertThat(committed)
                .isEqualTo(2);

        assertThat(commitmentCount)
                .isEqualTo(1);
    }
    private void seedActiveCatalogVariant(
            UUID tenantId,
            UUID variantId) {

        var productId =
                UUID.fromString(
                        "44444444-4444-4444-4444-444444444444");

        jdbcTemplate.update("""
                INSERT INTO catalog.products (
                    tenant_id,
                    id,
                    name,
                    slug,
                    description,
                    status
                )
                VALUES (
                    ?,
                    ?,
                    'Order fixture product',
                    'order_fixture_product',
                    NULL,
                    'ACTIVE'
                )
                """,
                tenantId,
                productId);

        jdbcTemplate.update("""
                INSERT INTO catalog.product_variants (
                    tenant_id,
                    id,
                    product_id,
                    sku,
                    status
                )
                VALUES (
                    ?,
                    ?,
                    ?,
                    'ORDER-FIXTURE-SKU',
                    'ACTIVE'
                )
                """,
                tenantId,
                variantId,
                productId);
    }
}

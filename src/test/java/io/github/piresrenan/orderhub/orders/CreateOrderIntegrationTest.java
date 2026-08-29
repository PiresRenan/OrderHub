package io.github.piresrenan.orderhub.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;

import io.github.piresrenan.orderhub.support.PostgreSqlTestConfiguration;

@SpringBootTest
@AutoConfigureMockMvc
@Import(PostgreSqlTestConfiguration.class)
class CreateOrderIntegrationTest {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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
        // Covers: HTTP -> controller -> use case -> domain -> PostgreSQL repository,
        // including persisted root and owned item.
        // Prevents: false-positive integration success where the API returns 201 but
        // persistence wiring does not actually write the aggregate.

        mockMvc.perform(post("/orders")
                        .header(
                                "X-Tenant-Id",
                                "11111111-1111-1111-1111-111111111111")
                        .contentType(MediaType.APPLICATION_JSON)
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
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.id").isString())
                .andExpect(jsonPath("$.tenantId")
                        .value("11111111-1111-1111-1111-111111111111"))
                .andExpect(jsonPath("$.customerId")
                        .value("22222222-2222-2222-2222-222222222222"))
                .andExpect(jsonPath("$.status")
                        .value("CREATED"));

        var tenantId = UUID.fromString(
                "11111111-1111-1111-1111-111111111111");

        var customerId = UUID.fromString(
                "22222222-2222-2222-2222-222222222222");

        var productId = UUID.fromString(
                "33333333-3333-3333-3333-333333333333");

        var persistedOrderCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM orders.orders
                WHERE tenant_id = ?
                  AND customer_id = ?
                  AND status = 'CREATED'
                """,
                Long.class,
                tenantId,
                customerId);

        var persistedItemCount = jdbcTemplate.queryForObject("""
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
                .isEqualTo(1L);

        assertThat(persistedItemCount)
                .isEqualTo(1L);
    }
}

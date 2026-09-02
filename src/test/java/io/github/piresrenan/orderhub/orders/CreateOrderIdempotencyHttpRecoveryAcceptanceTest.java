package io.github.piresrenan.orderhub.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doReturn;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

import io.micrometer.core.instrument.MeterRegistry;
import io.github.piresrenan.orderhub.security.support.RealJwtTestSupport;
import io.github.piresrenan.orderhub.support.PostgreSqlTestConfiguration;
import io.github.piresrenan.orderhub.users.application.port.in.FindTenantMembershipQuery;
import io.github.piresrenan.orderhub.users.application.port.in.FindTenantMembershipUseCase;
import io.github.piresrenan.orderhub.users.application.port.in.ResolveExternalIdentityQuery;
import io.github.piresrenan.orderhub.users.application.port.in.ResolveExternalIdentityUseCase;
import io.github.piresrenan.orderhub.users.application.port.in.ResolvedUserIdentity;
import io.github.piresrenan.orderhub.users.domain.model.TenantMembership;

@SpringBootTest(properties = {
        "orderhub.security.jwt.issuer=https://issuer.idempotency.test",
        "orderhub.security.jwt.audience=orderhub-api",
        "orderhub.security.jwt.jwk-set-uri=http://127.0.0.1:1/unused-idempotency-jwks"
})
@AutoConfigureMockMvc
@Import({
        PostgreSqlTestConfiguration.class,
        CreateOrderIdempotencyHttpRecoveryAcceptanceTest.RealJwtConfiguration.class
})
class CreateOrderIdempotencyHttpRecoveryAcceptanceTest {

    private static final String ISSUER =
            "https://issuer.idempotency.test";

    private static final String AUDIENCE =
            "orderhub-api";

    private static final String SUBJECT =
            "synthetic-idempotency-http-subject";

    private static final String METRIC =
            "orderhub.orders.idempotency";

    private static final UUID USER_ID =
            UUID.fromString(
                    "10000000-0000-0000-0000-000000000010");

    private static final UUID TENANT_ID =
            UUID.fromString(
                    "10000000-0000-0000-0000-000000000001");

    private static final UUID PRODUCT_ID =
            UUID.fromString(
                    "20000000-0000-0000-0000-000000000001");

    private static final UUID VARIANT_ID =
            UUID.fromString(
                    "30000000-0000-0000-0000-000000000001");

    private static final UUID CUSTOMER_ID =
            UUID.fromString(
                    "50000000-0000-0000-0000-000000000001");

    private static final RSAKey SIGNING_KEY =
            RealJwtTestSupport.generateRsaKey(
                    "orderhub-idempotency-http-key");

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private MeterRegistry meterRegistry;

    @MockitoBean
    private ResolveExternalIdentityUseCase externalIdentities;

    @MockitoBean
    private FindTenantMembershipUseCase memberships;

    @BeforeEach
    void resetState() {

        removeIdempotencyMeters();

        jdbcTemplate.update(
                """
                TRUNCATE TABLE
                    orders.order_request_idempotency,
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

        when(externalIdentities.resolve(
                any(ResolveExternalIdentityQuery.class)))
                .thenReturn(
                        Optional.of(
                                new ResolvedUserIdentity(
                                        USER_ID)));

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
                                USER_ID,
                                TENANT_ID));
    }

    @Test
    void sameKeyAndRequestReplaySame201WithoutDuplicateEffects()
            throws Exception {

        seedActiveCatalogAndInventory(
                10);

        var token =
                validToken();

        var key =
                "http-replay-key";

        performCreate(
                token,
                key,
                2)
                .andExpect(
                        status().isCreated())
                .andExpect(
                        jsonPath("$.tenantId")
                                .value(
                                        TENANT_ID.toString()))
                .andExpect(
                        jsonPath("$.customerId")
                                .value(
                                        CUSTOMER_ID.toString()))
                .andExpect(
                        jsonPath("$.status")
                                .value(
                                        "CREATED"))
                .andExpect(
                        jsonPath("$.allocationOutcome")
                                .value(
                                        "FULLY_ALLOCATED"));

        var orderId =
                singleOrderId();

        performCreate(
                token,
                key,
                2)
                .andExpect(
                        status().isCreated())
                .andExpect(
                        jsonPath("$.id")
                                .value(
                                        orderId.toString()))
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
                                        VARIANT_ID.toString()))
                .andExpect(
                        jsonPath("$.items[0].quantity")
                                .value(
                                        2));

        assertThat(orderCount())
                .isEqualTo(
                        1);

        assertThat(commitmentCount())
                .isEqualTo(
                        1);

        assertThat(committedQuantity())
                .isEqualTo(
                        2);

        assertThat(completedIdempotencyCount())
                .isEqualTo(
                        1);

        assertThat(processingIdempotencyCount())
                .isZero();

        assertCounter(
                "first_execution",
                1.0d);

        assertCounter(
                "replay",
                1.0d);

        assertIdempotencyMetricHasOnlyBoundedOutcomeTags();
    }

    @Test
    void rolledBackFirstAttemptDoesNotConsumeKeyAndRetryCanLaterSucceed()
            throws Exception {

        seedActiveCatalogAndInventory(
                0);

        var token =
                validToken();

        var key =
                "http-recovery-key";

        performCreate(
                token,
                key,
                2)
                .andExpect(
                        status().isConflict())
                .andExpect(
                        jsonPath("$.code")
                                .value(
                                        "INVENTORY_COMMITMENT_REJECTED"));

        assertThat(orderCount())
                .isZero();

        assertThat(commitmentCount())
                .isZero();

        assertThat(idempotencyCount())
                .as(
                        "A rolled-back PROCESSING acquisition must not become durable")
                .isZero();

        assertThat(processingIdempotencyCount())
                .isZero();

        jdbcTemplate.update(
                """
                UPDATE inventory.inventory_positions
                SET on_hand = 5
                WHERE tenant_id = ?
                  AND variant_id = ?
                """,
                TENANT_ID,
                VARIANT_ID);

        performCreate(
                token,
                key,
                2)
                .andExpect(
                        status().isCreated());

        var orderId =
                singleOrderId();

        performCreate(
                token,
                key,
                2)
                .andExpect(
                        status().isCreated())
                .andExpect(
                        jsonPath("$.id")
                                .value(
                                        orderId.toString()));

        assertThat(orderCount())
                .isEqualTo(
                        1);

        assertThat(commitmentCount())
                .isEqualTo(
                        1);

        assertThat(committedQuantity())
                .isEqualTo(
                        2);

        assertThat(completedIdempotencyCount())
                .isEqualTo(
                        1);

        assertThat(processingIdempotencyCount())
                .isZero();

        /*
         * The first business attempt rolled back, the second committed and the
         * third replayed. The replay metric therefore proves that recovery
         * reached the durable replay path instead of executing business effects
         * again.
         */
        assertCounter(
                "replay",
                1.0d);

        assertIdempotencyMetricHasOnlyBoundedOutcomeTags();
    }

    @Test
    void sameKeyDifferentRequestReturns422WithoutDuplicateEffects()
            throws Exception {

        seedActiveCatalogAndInventory(
                10);

        var token =
                validToken();

        var key =
                "http-conflict-key";

        performCreate(
                token,
                key,
                1)
                .andExpect(
                        status().isCreated());

        var firstOrderId =
                singleOrderId();

        performCreate(
                token,
                key,
                2)
                .andExpect(
                        status().is(422))
                .andExpect(
                        jsonPath("$.type")
                                .value(
                                        "urn:orderhub:problem:idempotency-key-reused"))
                .andExpect(
                        jsonPath("$.code")
                                .value(
                                        "IDEMPOTENCY_KEY_REUSED"));

        assertThat(singleOrderId())
                .isEqualTo(
                        firstOrderId);

        assertThat(orderCount())
                .isEqualTo(
                        1);

        assertThat(commitmentCount())
                .isEqualTo(
                        1);

        assertThat(committedQuantity())
                .isEqualTo(
                        1);

        assertThat(completedIdempotencyCount())
                .isEqualTo(
                        1);

        assertThat(processingIdempotencyCount())
                .isZero();

        assertCounter(
                "fingerprint_conflict",
                1.0d);

        assertIdempotencyMetricHasOnlyBoundedOutcomeTags();
    }

    private org.springframework.test.web.servlet.ResultActions performCreate(
            String token,
            String idempotencyKey,
            int quantity)
            throws Exception {

        return mockMvc.perform(
                post("/orders")
                        .header(
                                HttpHeaders.AUTHORIZATION,
                                bearer(
                                        token))
                        .header(
                                "X-Tenant-Id",
                                TENANT_ID)
                        .header(
                                "Idempotency-Key",
                                idempotencyKey)
                        .contentType(
                                MediaType.APPLICATION_JSON)
                        .content(
                                body(
                                        quantity)));
    }

    private void seedActiveCatalogAndInventory(
            long onHand) {

        jdbcTemplate.update(
                """
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
                    'Idempotency acceptance product',
                    'idempotency-acceptance-product',
                    NULL,
                    'ACTIVE'
                )
                """,
                TENANT_ID,
                PRODUCT_ID);

        jdbcTemplate.update(
                """
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
                    'IDEMPOTENCY-ACCEPTANCE-VARIANT',
                    'ACTIVE'
                )
                """,
                TENANT_ID,
                VARIANT_ID,
                PRODUCT_ID);

        jdbcTemplate.update(
                """
                INSERT INTO inventory.tenant_policies (
                    tenant_id,
                    policy
                )
                VALUES (?, 'DENY')
                """,
                TENANT_ID);

        jdbcTemplate.update(
                """
                INSERT INTO inventory.inventory_positions (
                    tenant_id,
                    variant_id,
                    on_hand,
                    committed,
                    backordered,
                    safety_stock
                )
                VALUES (?, ?, ?, 0, 0, 0)
                """,
                TENANT_ID,
                VARIANT_ID,
                onHand);
    }

    private UUID singleOrderId() {

        return jdbcTemplate.queryForObject(
                """
                SELECT id
                FROM orders.orders
                WHERE tenant_id = ?
                """,
                UUID.class,
                TENANT_ID);
    }

    private long orderCount() {

        return scalar(
                """
                SELECT COUNT(*)
                FROM orders.orders
                WHERE tenant_id = ?
                """,
                TENANT_ID);
    }

    private long commitmentCount() {

        return scalar(
                """
                SELECT COUNT(*)
                FROM inventory.inventory_commitments
                WHERE tenant_id = ?
                  AND variant_id = ?
                """,
                TENANT_ID,
                VARIANT_ID);
    }

    private long committedQuantity() {

        return scalar(
                """
                SELECT committed
                FROM inventory.inventory_positions
                WHERE tenant_id = ?
                  AND variant_id = ?
                """,
                TENANT_ID,
                VARIANT_ID);
    }

    private long idempotencyCount() {

        return scalar(
                """
                SELECT COUNT(*)
                FROM orders.order_request_idempotency
                WHERE tenant_id = ?
                """,
                TENANT_ID);
    }

    private long completedIdempotencyCount() {

        return scalar(
                """
                SELECT COUNT(*)
                FROM orders.order_request_idempotency
                WHERE tenant_id = ?
                  AND state = 'COMPLETED'
                """,
                TENANT_ID);
    }

    private long processingIdempotencyCount() {

        return scalar(
                """
                SELECT COUNT(*)
                FROM orders.order_request_idempotency
                WHERE tenant_id = ?
                  AND state = 'PROCESSING'
                """,
                TENANT_ID);
    }

    private long scalar(
            String sql,
            Object... arguments) {

        return jdbcTemplate.queryForObject(
                sql,
                Long.class,
                arguments);
    }

    private void assertCounter(
            String outcome,
            double expectedCount) {

        var counter =
                meterRegistry
                        .find(
                                METRIC)
                        .tag(
                                "outcome",
                                outcome)
                        .counter();

        assertThat(counter)
                .as(
                        "%s{outcome=%s}",
                        METRIC,
                        outcome)
                .isNotNull();

        assertThat(counter.count())
                .isEqualTo(
                        expectedCount);
    }

    private void assertIdempotencyMetricHasOnlyBoundedOutcomeTags() {

        var meters =
                meterRegistry
                        .getMeters()
                        .stream()
                        .filter(meter ->
                                METRIC.equals(
                                        meter.getId()
                                                .getName()))
                        .toList();

        assertThat(meters)
                .isNotEmpty();

        var allowed =
                List.of(
                        "first_execution",
                        "replay",
                        "fingerprint_conflict",
                        "in_progress_conflict",
                        "technical_failure");

        for (var meter :
                meters) {

            assertThat(
                    meter.getId()
                            .getTags())
                    .hasSize(
                            1);

            var tag =
                    meter.getId()
                            .getTags()
                            .getFirst();

            assertThat(tag.getKey())
                    .isEqualTo(
                            "outcome");

            assertThat(tag.getValue())
                    .isIn(
                            allowed);
        }
    }

    private void removeIdempotencyMeters() {

        var meters =
                List.copyOf(
                        meterRegistry.getMeters());

        for (var meter :
                meters) {

            if (METRIC.equals(
                    meter.getId()
                            .getName())) {

                meterRegistry.remove(
                        meter);
            }
        }
    }

    private static String body(
            int quantity) {

        return """
                {
                  "customerId": "%s",
                  "items": [{
                    "variantId": "%s",
                    "quantity": %d
                  }]
                }
                """.formatted(
                CUSTOMER_ID,
                VARIANT_ID,
                quantity);
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

package io.github.piresrenan.orderhub.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowable;

import java.util.List;
import java.util.UUID;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderCommand;
import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderIdempotencyKeyDigest;
import io.github.piresrenan.orderhub.orders.support.TestCreateOrderIdempotencyKeyDigests;
import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderUseCase;
import io.github.piresrenan.orderhub.support.PostgreSqlTestConfiguration;

/**
 * Adversarial RED proof for OH-011 Catalog sellability.
 *
 * <p>These tests intentionally exercise the current public Order boundary
 * against real PostgreSQL. They must fail on the reviewed implementation
 * because Order creation currently does not consult Catalog before committing
 * Inventory.</p>
 */
@SpringBootTest
@Import(PostgreSqlTestConfiguration.class)
class CreateOrderCatalogSellabilityAcceptanceTest {

    private static final UUID TENANT_ID =
            UUID.fromString(
                    "10000000-0000-0000-0000-000000000001");

    private static final UUID OTHER_TENANT_ID =
            UUID.fromString(
                    "10000000-0000-0000-0000-000000000002");

    private static final UUID PRODUCT_ID =
            UUID.fromString(
                    "20000000-0000-0000-0000-000000000001");

    private static final UUID VARIANT_ID =
            UUID.fromString(
                    "30000000-0000-0000-0000-000000000001");

    private static final UUID CUSTOMER_ID =
            UUID.fromString(
                    "50000000-0000-0000-0000-000000000001");

    @Autowired
    private CreateOrderUseCase createOrderUseCase;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @BeforeEach
    void cleanBusinessState() {

        /*
         * All Catalog-owned FK participants are included explicitly.
         * No CASCADE is used so this fixture cannot hide an unexpected
         * cross-module foreign-key dependency.
         */
        jdbcTemplate.update("""
                TRUNCATE TABLE
                    catalog.media,
                    catalog.variant_base_prices,
                    catalog.product_variant_attributes,
                    catalog.product_categories,
                    catalog.product_variants,
                    catalog.categories,
                    catalog.products,
                    inventory.inventory_commitments,
                    inventory.inventory_positions,
                    inventory.tenant_policies,
                    orders.order_request_idempotency,
                    orders.order_items,
                    orders.orders
                """);
    }

    @Test
    void activeProductAndActiveVariantRemainValidPositiveControl() {

        insertProduct(
                TENANT_ID,
                PRODUCT_ID,
                "ACTIVE");

        insertVariant(
                TENANT_ID,
                VARIANT_ID,
                PRODUCT_ID,
                "ACTIVE");

        prepareInventory(
                TENANT_ID,
                VARIANT_ID);

        var result =
                createOrderUseCase.create(
                        command(
                                TENANT_ID,
                                VARIANT_ID));

        assertThat(result)
                .as("ACTIVE/ACTIVE is the positive control")
                .isNotNull();

        assertThat(orderCount(TENANT_ID))
                .isEqualTo(1);

        assertThat(committedQuantity(
                TENANT_ID,
                VARIANT_ID))
                .isEqualTo(1);

        assertThat(commitmentCount(
                TENANT_ID,
                VARIANT_ID))
                .isEqualTo(1);
    }

    @ParameterizedTest(
            name = "non-ACTIVE Variant status {0} must fail closed")
    @ValueSource(
            strings = {
                    "DRAFT",
                    "INACTIVE",
                    "ARCHIVED"
            })
    void rejectsNonActiveVariantWithoutDurableEffects(
            String variantStatus) {

        insertProduct(
                TENANT_ID,
                PRODUCT_ID,
                "ACTIVE");

        insertVariant(
                TENANT_ID,
                VARIANT_ID,
                PRODUCT_ID,
                variantStatus);

        prepareInventory(
                TENANT_ID,
                VARIANT_ID);

        assertRejectedWithZeroDurableEffects(
                TENANT_ID,
                VARIANT_ID,
                "Variant status " + variantStatus);
    }

    @ParameterizedTest(
            name = "non-ACTIVE Product status {0} must fail closed")
    @ValueSource(
            strings = {
                    "DRAFT",
                    "ARCHIVED"
            })
    void rejectsActiveVariantWhoseProductIsNotActiveWithoutDurableEffects(
            String productStatus) {

        insertProduct(
                TENANT_ID,
                PRODUCT_ID,
                productStatus);

        insertVariant(
                TENANT_ID,
                VARIANT_ID,
                PRODUCT_ID,
                "ACTIVE");

        prepareInventory(
                TENANT_ID,
                VARIANT_ID);

        assertRejectedWithZeroDurableEffects(
                TENANT_ID,
                VARIANT_ID,
                "Product status " + productStatus);
    }

    @Test
    void rejectsMissingCatalogVariantEvenWhenInventoryPositionExists() {

        /*
         * This state is deliberately possible because Inventory must not own a
         * cross-module FK into Catalog. Inventory existence therefore cannot
         * prove commercial orderability.
         */
        prepareInventory(
                TENANT_ID,
                VARIANT_ID);

        assertRejectedWithZeroDurableEffects(
                TENANT_ID,
                VARIANT_ID,
                "Variant missing from Catalog");
    }

    @Test
    void rejectsVariantOwnedByAnotherTenantEvenWhenLocalInventoryExists() {

        insertProduct(
                OTHER_TENANT_ID,
                PRODUCT_ID,
                "ACTIVE");

        insertVariant(
                OTHER_TENANT_ID,
                VARIANT_ID,
                PRODUCT_ID,
                "ACTIVE");

        /*
         * Deliberately create a position for the requested Tenant using the
         * same Variant UUID. The Catalog boundary, not Inventory identity
         * coincidence, must determine whether the Variant is orderable.
         */
        prepareInventory(
                TENANT_ID,
                VARIANT_ID);

        assertRejectedWithZeroDurableEffects(
                TENANT_ID,
                VARIANT_ID,
                "Variant belongs only to another Tenant");
    }

    private void assertRejectedWithZeroDurableEffects(
            UUID tenantId,
            UUID variantId,
            String scenario) {

        var failure =
                catchThrowable(() ->
                        createOrderUseCase.create(
                                command(
                                        tenantId,
                                        variantId)));

        /*
         * Soft assertions deliberately preserve all evidence from the same
         * scenario. On the reviewed implementation we expect to observe not
         * only absence of rejection but also the resulting durable Order,
         * Inventory mutation and commitment ledger entry.
         */
        var softly =
                new SoftAssertions();

        softly.assertThat(failure)
                .as("%s must be rejected", scenario)
                .isNotNull();

        softly.assertThat(orderCount(tenantId))
                .as("%s must leave no durable Order", scenario)
                .isZero();

        softly.assertThat(committedQuantity(
                tenantId,
                variantId))
                .as("%s must leave Inventory unchanged", scenario)
                .isZero();

        softly.assertThat(commitmentCount(
                tenantId,
                variantId))
                .as("%s must leave no Inventory commitment ledger", scenario)
                .isZero();

        softly.assertAll();
    }

    private void insertProduct(
            UUID tenantId,
            UUID productId,
            String status) {

        jdbcTemplate.update("""
                INSERT INTO catalog.products (
                    tenant_id,
                    id,
                    name,
                    slug,
                    description,
                    status
                )
                VALUES (?, ?, ?, ?, NULL, ?)
                """,
                tenantId,
                productId,
                "RED Product",
                "red-product",
                status);
    }

    private void insertVariant(
            UUID tenantId,
            UUID variantId,
            UUID productId,
            String status) {

        jdbcTemplate.update("""
                INSERT INTO catalog.product_variants (
                    tenant_id,
                    id,
                    product_id,
                    sku,
                    status
                )
                VALUES (?, ?, ?, ?, ?)
                """,
                tenantId,
                variantId,
                productId,
                "SKU-RED",
                status);
    }

    private void prepareInventory(
            UUID tenantId,
            UUID variantId) {

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
    }

    private static CreateOrderCommand command(
            UUID tenantId,
            UUID variantId) {

        return new CreateOrderCommand(
                tenantId,
                CUSTOMER_ID,
                List.of(
                        new CreateOrderCommand.Item(
                                variantId,
                                1)),
                TestCreateOrderIdempotencyKeyDigests.from(
                        "catalog-sellability:"
                                + tenantId
                                + ":"
                                + variantId));
    }

    private long orderCount(
            UUID tenantId) {

        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM orders.orders
                WHERE tenant_id = ?
                """,
                Long.class,
                tenantId);
    }

    private long committedQuantity(
            UUID tenantId,
            UUID variantId) {

        return jdbcTemplate.queryForObject("""
                SELECT committed
                FROM inventory.inventory_positions
                WHERE tenant_id = ?
                  AND variant_id = ?
                """,
                Long.class,
                tenantId,
                variantId);
    }

    private long commitmentCount(
            UUID tenantId,
            UUID variantId) {

        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM inventory.inventory_commitments
                WHERE tenant_id = ?
                  AND variant_id = ?
                """,
                Long.class,
                tenantId,
                variantId);
    }
}

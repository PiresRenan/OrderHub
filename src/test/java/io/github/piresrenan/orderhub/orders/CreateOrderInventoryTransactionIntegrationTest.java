package io.github.piresrenan.orderhub.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.piresrenan.orderhub.inventory.application.port.in.InventoryCommitmentRejectedException;
import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderCommand;
import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderAllocationOutcome;
import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderUseCase;
import io.github.piresrenan.orderhub.orders.domain.model.OrderStatus;
import io.github.piresrenan.orderhub.support.PostgreSqlTestConfiguration;

@SpringBootTest
@Import(PostgreSqlTestConfiguration.class)
class CreateOrderInventoryTransactionIntegrationTest {

    private static final UUID TENANT_ID =
            UUID.fromString(
                    "10000000-0000-0000-0000-000000000001");

    private static final UUID PRODUCT_ID =
            UUID.fromString(
                    "20000000-0000-0000-0000-000000000001");

    private static final UUID VARIANT_A =
            UUID.fromString(
                    "30000000-0000-0000-0000-000000000001");

    private static final UUID VARIANT_B =
            UUID.fromString(
                    "30000000-0000-0000-0000-000000000002");

    @Autowired
    private CreateOrderUseCase createOrderUseCase;

    @Autowired
    private JdbcTemplate jdbcTemplate;

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

        seedActiveCatalog();
    }

    @Test
    void commitsOrderInventoryPositionAndLedgerInOneTransaction() {

        insertPolicy(
                "DENY");

        insertPosition(
                VARIANT_A,
                10);

        var result =
                createOrderUseCase.create(
                        command(
                                UUID.randomUUID(),
                                List.of(
                                        item(
                                                VARIANT_A,
                                                3))));

        assertThat(
                count("""
                        SELECT COUNT(*)
                        FROM orders.orders
                        WHERE tenant_id = ?
                          AND id = ?
                        """,
                        TENANT_ID,
                        result.order().id()))
                .isEqualTo(1);

        assertThat(
                count("""
                        SELECT COUNT(*)
                        FROM orders.order_items
                        WHERE tenant_id = ?
                          AND order_id = ?
                          AND variant_id = ?
                          AND quantity = 3
                        """,
                        TENANT_ID,
                        result.order().id(),
                        VARIANT_A))
                .isEqualTo(1);

        assertThat(
                scalar("""
                        SELECT committed
                        FROM inventory.inventory_positions
                        WHERE tenant_id = ?
                          AND variant_id = ?
                        """,
                        TENANT_ID,
                        VARIANT_A))
                .isEqualTo(3);

        assertThat(
                count("""
                        SELECT COUNT(*)
                        FROM inventory.inventory_commitments
                        WHERE tenant_id = ?
                          AND order_id = ?
                          AND variant_id = ?
                          AND requested_quantity = 3
                          AND allocated_quantity = 3
                          AND backordered_quantity = 0
                        """,
                        TENANT_ID,
                        result.order().id(),
                        VARIANT_A))
                .isEqualTo(1);
    }

    @Test
    void rollsBackOrderEarlierInventoryMutationAndLedgerWhenLaterVariantIsRejected() {

        insertPolicy(
                "DENY");

        insertPosition(
                VARIANT_A,
                5);

        insertPosition(
                VARIANT_B,
                0);

        assertThatThrownBy(() ->
                createOrderUseCase.create(
                        command(
                                UUID.randomUUID(),
                                List.of(
                                        item(
                                                VARIANT_B,
                                                1),
                                        item(
                                                VARIANT_A,
                                                2)))))
                .isInstanceOf(
                        InventoryCommitmentRejectedException.class);

        assertThat(
                count("""
                        SELECT COUNT(*)
                        FROM orders.orders
                        WHERE tenant_id = ?
                        """,
                        TENANT_ID))
                .isZero();

        assertThat(
                scalar("""
                        SELECT committed
                        FROM inventory.inventory_positions
                        WHERE tenant_id = ?
                          AND variant_id = ?
                        """,
                        TENANT_ID,
                        VARIANT_A))
                .isZero();

        assertThat(
                scalar("""
                        SELECT committed
                        FROM inventory.inventory_positions
                        WHERE tenant_id = ?
                          AND variant_id = ?
                        """,
                        TENANT_ID,
                        VARIANT_B))
                .isZero();

        assertThat(
                count("""
                        SELECT COUNT(*)
                        FROM inventory.inventory_commitments
                        WHERE tenant_id = ?
                        """,
                        TENANT_ID))
                .isZero();
    }

    @Test
    void aggregatesDuplicateOrderLinesBeforeInventoryMutationAndLedger() {

        insertPolicy(
                "DENY");

        insertPosition(
                VARIANT_A,
                5);

        var result =
                createOrderUseCase.create(
                        command(
                                UUID.randomUUID(),
                                List.of(
                                        item(
                                                VARIANT_A,
                                                2),
                                        item(
                                                VARIANT_A,
                                                3))));

        assertThat(
                count("""
                        SELECT COUNT(*)
                        FROM orders.order_items
                        WHERE tenant_id = ?
                          AND order_id = ?
                          AND variant_id = ?
                        """,
                        TENANT_ID,
                        result.order().id(),
                        VARIANT_A))
                .isEqualTo(2);

        assertThat(
                scalar("""
                        SELECT committed
                        FROM inventory.inventory_positions
                        WHERE tenant_id = ?
                          AND variant_id = ?
                        """,
                        TENANT_ID,
                        VARIANT_A))
                .isEqualTo(5);

        assertThat(
                count("""
                        SELECT COUNT(*)
                        FROM inventory.inventory_commitments
                        WHERE tenant_id = ?
                          AND order_id = ?
                          AND variant_id = ?
                          AND requested_quantity = 5
                        """,
                        TENANT_ID,
                        result.order().id(),
                        VARIANT_A))
                .isEqualTo(1);
    }

    @Test
    void allowsBackorderWithoutChangingOrderStatus() {

        insertPolicy(
                "ALLOW_BACKORDER");

        insertPosition(
                VARIANT_A,
                2);

        var result =
                createOrderUseCase.create(
                        command(
                                UUID.randomUUID(),
                                List.of(
                                        item(
                                                VARIANT_A,
                                                5))));

        assertThat(result.order().status())
                .isEqualTo(
                        OrderStatus.CREATED);

        assertThat(result.allocationOutcome())
                .isEqualTo(
                        CreateOrderAllocationOutcome.PARTIALLY_BACKORDERED);

        assertThat(
                scalar("""
                        SELECT committed
                        FROM inventory.inventory_positions
                        WHERE tenant_id = ?
                          AND variant_id = ?
                        """,
                        TENANT_ID,
                        VARIANT_A))
                .isEqualTo(2);

        assertThat(
                scalar("""
                        SELECT backordered
                        FROM inventory.inventory_positions
                        WHERE tenant_id = ?
                          AND variant_id = ?
                        """,
                        TENANT_ID,
                        VARIANT_A))
                .isEqualTo(3);

        assertThat(
                count("""
                        SELECT COUNT(*)
                        FROM inventory.inventory_commitments
                        WHERE tenant_id = ?
                          AND order_id = ?
                          AND variant_id = ?
                          AND requested_quantity = 5
                          AND allocated_quantity = 2
                          AND backordered_quantity = 3
                        """,
                        TENANT_ID,
                        result.order().id(),
                        VARIANT_A))
                .isEqualTo(1);
    }

    @Test
    void failsClosedAndRollsBackOrderWhenTenantPolicyIsMissing() {

        insertPosition(
                VARIANT_A,
                10);

        assertThatThrownBy(() ->
                createOrderUseCase.create(
                        command(
                                UUID.randomUUID(),
                                List.of(
                                        item(
                                                VARIANT_A,
                                                1)))))
                .isInstanceOf(
                        InventoryCommitmentRejectedException.class);

        assertThat(
                count("""
                        SELECT COUNT(*)
                        FROM orders.orders
                        WHERE tenant_id = ?
                        """,
                        TENANT_ID))
                .isZero();

        assertThat(
                scalar("""
                        SELECT committed
                        FROM inventory.inventory_positions
                        WHERE tenant_id = ?
                          AND variant_id = ?
                        """,
                        TENANT_ID,
                        VARIANT_A))
                .isZero();
    }

    @Test
    void failsClosedAndRollsBackOrderWhenInventoryPositionIsMissing() {

        insertPolicy(
                "DENY");

        assertThatThrownBy(() ->
                createOrderUseCase.create(
                        command(
                                UUID.randomUUID(),
                                List.of(
                                        item(
                                                VARIANT_A,
                                                1)))))
                .isInstanceOf(
                        InventoryCommitmentRejectedException.class);

        assertThat(
                count("""
                        SELECT COUNT(*)
                        FROM orders.orders
                        WHERE tenant_id = ?
                        """,
                        TENANT_ID))
                .isZero();

        assertThat(
                count("""
                        SELECT COUNT(*)
                        FROM inventory.inventory_commitments
                        WHERE tenant_id = ?
                        """,
                        TENANT_ID))
                .isZero();
    }

    @Test
    void concurrentOrdersForLastUnitProduceExactlyOneCommittedOrder() throws Exception {

        insertPolicy(
                "DENY");

        insertPosition(
                VARIANT_A,
                1);

        var executor =
                Executors.newFixedThreadPool(
                        2);

        var start =
                new CountDownLatch(
                        1);

        try {

            var first =
                    executor.submit(() -> {

                        start.await();

                        return attempt(
                                UUID.fromString(
                                        "50000000-0000-0000-0000-000000000001"));
                    });

            var second =
                    executor.submit(() -> {

                        start.await();

                        return attempt(
                                UUID.fromString(
                                        "50000000-0000-0000-0000-000000000002"));
                    });

            start.countDown();

            var outcomes =
                    List.of(
                            first.get(
                                    15,
                                    TimeUnit.SECONDS),
                            second.get(
                                    15,
                                    TimeUnit.SECONDS));

            assertThat(outcomes)
                    .filteredOn(
                            Outcome::success)
                    .hasSize(1);

            assertThat(outcomes)
                    .filteredOn(
                            outcome ->
                                    outcome.failure()
                                            instanceof InventoryCommitmentRejectedException)
                    .hasSize(1);

        } finally {

            executor.shutdownNow();
        }

        assertThat(
                count("""
                        SELECT COUNT(*)
                        FROM orders.orders
                        WHERE tenant_id = ?
                        """,
                        TENANT_ID))
                .isEqualTo(1);

        assertThat(
                scalar("""
                        SELECT committed
                        FROM inventory.inventory_positions
                        WHERE tenant_id = ?
                          AND variant_id = ?
                        """,
                        TENANT_ID,
                        VARIANT_A))
                .isEqualTo(1);

        assertThat(
                count("""
                        SELECT COUNT(*)
                        FROM inventory.inventory_commitments
                        WHERE tenant_id = ?
                          AND variant_id = ?
                        """,
                        TENANT_ID,
                        VARIANT_A))
                .isEqualTo(1);
    }

    private Outcome attempt(
            UUID customerId) {

        try {

            createOrderUseCase.create(
                    command(
                            customerId,
                            List.of(
                                    item(
                                            VARIANT_A,
                                            1))));

            return new Outcome(
                    true,
                    null);

        } catch (Throwable failure) {

            return new Outcome(
                    false,
                    failure);
        }
    }

    private static CreateOrderCommand command(
            UUID customerId,
            List<CreateOrderCommand.Item> items) {

        return new CreateOrderCommand(
                TENANT_ID,
                customerId,
                items);
    }

    private static CreateOrderCommand.Item item(
            UUID variantId,
            int quantity) {

        return new CreateOrderCommand.Item(
                variantId,
                quantity);
    }

    private void seedActiveCatalog() {

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
                    'Inventory transaction fixture product',
                    'inventory_transaction_fixture_product',
                    NULL,
                    'ACTIVE'
                )
                """,
                TENANT_ID,
                PRODUCT_ID);

        insertActiveCatalogVariant(
                VARIANT_A,
                "TRANSACTION-VARIANT-A");

        insertActiveCatalogVariant(
                VARIANT_B,
                "TRANSACTION-VARIANT-B");
    }

    private void insertActiveCatalogVariant(
            UUID variantId,
            String sku) {

        jdbcTemplate.update("""
                INSERT INTO catalog.product_variants (
                    tenant_id,
                    id,
                    product_id,
                    sku,
                    status
                )
                VALUES (?, ?, ?, ?, 'ACTIVE')
                """,
                TENANT_ID,
                variantId,
                PRODUCT_ID,
                sku);
    }

    private void insertPolicy(
            String policy) {

        jdbcTemplate.update("""
                INSERT INTO inventory.tenant_policies (
                    tenant_id,
                    policy
                )
                VALUES (?, ?)
                """,
                TENANT_ID,
                policy);
    }

    private void insertPosition(
            UUID variantId,
            long onHand) {

        jdbcTemplate.update("""
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
                variantId,
                onHand);
    }

    private long count(
            String sql,
            Object... arguments) {

        return jdbcTemplate.queryForObject(
                sql,
                Long.class,
                arguments);
    }

    private long scalar(
            String sql,
            Object... arguments) {

        return jdbcTemplate.queryForObject(
                sql,
                Long.class,
                arguments);
    }

    private record Outcome(
            boolean success,
            Throwable failure) {
    }
}

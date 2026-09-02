package io.github.piresrenan.orderhub.orders;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import javax.sql.DataSource;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.piresrenan.orderhub.inventory.application.port.in.InventoryOperationException;
import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderCommand;
import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderIdempotencyKeyDigest;
import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderUseCase;
import io.github.piresrenan.orderhub.orders.application.port.out.OrderPersistenceException;
import io.github.piresrenan.orderhub.orders.application.port.out.TransactionExecutionException;
import io.github.piresrenan.orderhub.support.PostgreSqlTestConfiguration;

/**
 * OH-011 acceptance evidence for full Order + Inventory concurrency,
 * rollback and bounded lock-wait behavior.
 */
@SpringBootTest(
        properties =
                "orderhub.orders.transaction.timeout=2s")
@Import(PostgreSqlTestConfiguration.class)
class CreateOrderInventoryConcurrencyAcceptanceTest {

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

    private static final UUID CUSTOMER_A =
            UUID.fromString(
                    "50000000-0000-0000-0000-000000000001");

    private static final UUID CUSTOMER_B =
            UUID.fromString(
                    "50000000-0000-0000-0000-000000000002");

    private static final UUID FORBIDDEN_CUSTOMER =
            UUID.fromString(
                    "90000000-0000-0000-0000-000000000001");

    @Autowired
    private CreateOrderUseCase createOrderUseCase;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void cleanBusinessState() {

        dropSyntheticDatabaseArtifacts();

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

    @AfterEach
    void removeSyntheticDatabaseArtifacts() {

        dropSyntheticDatabaseArtifacts();
    }

    // ---------------------------------------------------------------------
    // Acceptance #2
    // ---------------------------------------------------------------------

    @Test
    void competingAllowBackorderOrdersProduceConsistentCommittedAndBackorderedTotals()
            throws Exception {

        insertPolicy(
                "ALLOW_BACKORDER");

        insertPosition(
                VARIANT_A,
                1);

        var attempts =
                runConcurrently(
                        () ->
                                createOrderUseCase.create(
                                        command(
                                                CUSTOMER_A,
                                                List.of(
                                                        item(
                                                                VARIANT_A,
                                                                1)))),
                        () ->
                                createOrderUseCase.create(
                                        command(
                                                CUSTOMER_B,
                                                List.of(
                                                        item(
                                                                VARIANT_A,
                                                                1)))));

        assertThat(attempts)
                .allSatisfy(attempt ->
                        assertThat(attempt.failure())
                                .isNull());

        assertThat(
                count("""
                        SELECT COUNT(*)
                        FROM orders.orders
                        WHERE tenant_id = ?
                        """,
                        TENANT_ID))
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
                .isEqualTo(1);

        assertThat(
                scalar("""
                        SELECT backordered
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
                .isEqualTo(2);

        assertThat(
                scalar("""
                        SELECT COALESCE(SUM(allocated_quantity), 0)
                        FROM inventory.inventory_commitments
                        WHERE tenant_id = ?
                          AND variant_id = ?
                        """,
                        TENANT_ID,
                        VARIANT_A))
                .isEqualTo(1);

        assertThat(
                scalar("""
                        SELECT COALESCE(SUM(backordered_quantity), 0)
                        FROM inventory.inventory_commitments
                        WHERE tenant_id = ?
                          AND variant_id = ?
                        """,
                        TENANT_ID,
                        VARIANT_A))
                .isEqualTo(1);

        assertThat(
                scalar("""
                        SELECT COALESCE(SUM(requested_quantity), 0)
                        FROM inventory.inventory_commitments
                        WHERE tenant_id = ?
                          AND variant_id = ?
                        """,
                        TENANT_ID,
                        VARIANT_A))
                .isEqualTo(2);
    }

    // ---------------------------------------------------------------------
    // Acceptance #5
    // ---------------------------------------------------------------------

    @Test
    void forcedOrderPersistenceFailureProducesZeroInventoryEffects() {

        insertPolicy(
                "DENY");

        insertPosition(
                VARIANT_A,
                10);

        jdbcTemplate.execute("""
                ALTER TABLE orders.orders
                ADD CONSTRAINT ck_test_force_order_failure
                CHECK (
                    customer_id <>
                        '90000000-0000-0000-0000-000000000001'::uuid
                )
                """);

        try {
            assertThatThrownBy(() ->
                    createOrderUseCase.create(
                            command(
                                    FORBIDDEN_CUSTOMER,
                                    List.of(
                                            item(
                                                    VARIANT_A,
                                                    3)))))
                    .isInstanceOf(
                            OrderPersistenceException.class)
                    .hasMessage(
                            "Order persistence operation failed.");

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
                            SELECT backordered
                            FROM inventory.inventory_positions
                            WHERE tenant_id = ?
                              AND variant_id = ?
                            """,
                            TENANT_ID,
                            VARIANT_A))
                    .isZero();

            assertThat(
                    count("""
                            SELECT COUNT(*)
                            FROM inventory.inventory_commitments
                            WHERE tenant_id = ?
                            """,
                            TENANT_ID))
                    .isZero();
        } finally {
            jdbcTemplate.execute("""
                    ALTER TABLE orders.orders
                    DROP CONSTRAINT IF EXISTS
                        ck_test_force_order_failure
                    """);
        }
    }

    // ---------------------------------------------------------------------
    // Acceptance #6
    // ---------------------------------------------------------------------

    @Test
    void forcedInventoryLedgerFailureRollsBackOrderAndEarlierPositionMutation() {

        insertPolicy(
                "DENY");

        insertPosition(
                VARIANT_A,
                20);

        jdbcTemplate.execute("""
                ALTER TABLE inventory.inventory_commitments
                ADD CONSTRAINT ck_test_force_inventory_failure
                CHECK (requested_quantity <> 13)
                """);

        try {
            assertThatThrownBy(() ->
                    createOrderUseCase.create(
                            command(
                                    CUSTOMER_A,
                                    List.of(
                                            item(
                                                    VARIANT_A,
                                                    13)))))
                    .isInstanceOf(
                            InventoryOperationException.class)
                    .hasMessage(
                            "Inventory operation could not be completed.");

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
                            FROM orders.order_items
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
                            SELECT backordered
                            FROM inventory.inventory_positions
                            WHERE tenant_id = ?
                              AND variant_id = ?
                            """,
                            TENANT_ID,
                            VARIANT_A))
                    .isZero();

            assertThat(
                    count("""
                            SELECT COUNT(*)
                            FROM inventory.inventory_commitments
                            WHERE tenant_id = ?
                            """,
                            TENANT_ID))
                    .isZero();
        } finally {
            jdbcTemplate.execute("""
                    ALTER TABLE inventory.inventory_commitments
                    DROP CONSTRAINT IF EXISTS
                        ck_test_force_inventory_failure
                    """);
        }
    }

    // ---------------------------------------------------------------------
    // Acceptance #7
    // ---------------------------------------------------------------------

    @Test
    void inverseRequestOrderingCompletesWithoutApplicationDefinedLockCycle()
            throws Exception {

        insertPolicy(
                "DENY");

        insertPosition(
                VARIANT_A,
                2);

        insertPosition(
                VARIANT_B,
                2);

        jdbcTemplate.execute("""
                CREATE OR REPLACE FUNCTION
                    inventory.oh011_test_pause_position_update()
                RETURNS trigger
                LANGUAGE plpgsql
                AS $$
                BEGIN
                    PERFORM pg_sleep(0.250);
                    RETURN NEW;
                END;
                $$
                """);

        jdbcTemplate.execute("""
                CREATE TRIGGER
                    oh011_test_pause_position_update
                BEFORE UPDATE
                ON inventory.inventory_positions
                FOR EACH ROW
                EXECUTE FUNCTION
                    inventory.oh011_test_pause_position_update()
                """);

        try {
            var attempts =
                    runConcurrently(
                            () ->
                                    createOrderUseCase.create(
                                            command(
                                                    CUSTOMER_A,
                                                    List.of(
                                                            item(
                                                                    VARIANT_A,
                                                                    1),
                                                            item(
                                                                    VARIANT_B,
                                                                    1)))),
                            () ->
                                    createOrderUseCase.create(
                                            command(
                                                    CUSTOMER_B,
                                                    List.of(
                                                            item(
                                                                    VARIANT_B,
                                                                    1),
                                                            item(
                                                                    VARIANT_A,
                                                                    1)))));

            assertThat(attempts)
                    .allSatisfy(attempt ->
                            assertThat(attempt.failure())
                                    .isNull());

            assertThat(
                    count("""
                            SELECT COUNT(*)
                            FROM orders.orders
                            WHERE tenant_id = ?
                            """,
                            TENANT_ID))
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
                    .isEqualTo(2);

            assertThat(
                    scalar("""
                            SELECT committed
                            FROM inventory.inventory_positions
                            WHERE tenant_id = ?
                              AND variant_id = ?
                            """,
                            TENANT_ID,
                            VARIANT_B))
                    .isEqualTo(2);

            assertThat(
                    count("""
                            SELECT COUNT(*)
                            FROM inventory.inventory_commitments
                            WHERE tenant_id = ?
                            """,
                            TENANT_ID))
                    .isEqualTo(4);
        } finally {
            dropSyntheticDelayTrigger();
        }
    }

    // ---------------------------------------------------------------------
    // Acceptance #8
    // ---------------------------------------------------------------------

    @Test
    void configuredTransactionTimeoutTerminatesHeldInventoryLock()
            throws Exception {

        insertPolicy(
                "DENY");

        insertPosition(
                VARIANT_A,
                1);

        var executor =
                Executors.newSingleThreadExecutor();

        try (var lockConnection =
                dataSource.getConnection()) {

            lockConnection.setAutoCommit(
                    false);

            try (var lockStatement =
                    lockConnection.prepareStatement("""
                            SELECT variant_id
                            FROM inventory.inventory_positions
                            WHERE tenant_id = ?
                              AND variant_id = ?
                            FOR UPDATE
                            """)) {

                lockStatement.setObject(
                        1,
                        TENANT_ID);

                lockStatement.setObject(
                        2,
                        VARIANT_A);

                try (var resultSet =
                        lockStatement.executeQuery()) {

                    assertThat(resultSet.next())
                            .isTrue();
                }
            }

            Future<Attempt> future =
                    executor.submit(() ->
                            attempt(() ->
                                    createOrderUseCase.create(
                                            command(
                                                    CUSTOMER_A,
                                                    List.of(
                                                            item(
                                                                    VARIANT_A,
                                                                    1))))));

            var startedAt =
                    System.nanoTime();

            Attempt outcome;

            try {
                outcome =
                        future.get(
                                5,
                                TimeUnit.SECONDS);
            } finally {
                lockConnection.rollback();

                executor.shutdownNow();

                executor.awaitTermination(
                        5,
                        TimeUnit.SECONDS);
            }

            var elapsedMillis =
                    TimeUnit.NANOSECONDS
                            .toMillis(
                                    System.nanoTime()
                                            - startedAt);

            assertThat(outcome.failure())
                    .isInstanceOfAny(
                            InventoryOperationException.class,
                            TransactionExecutionException.class);

            assertThat(elapsedMillis)
                    .as("configured transaction timeout bounds lock wait")
                    .isLessThan(5_000L);
        }

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
                count("""
                        SELECT COUNT(*)
                        FROM inventory.inventory_commitments
                        WHERE tenant_id = ?
                        """,
                        TENANT_ID))
                .isZero();
    }

    // ---------------------------------------------------------------------
    // Concurrency helpers
    // ---------------------------------------------------------------------

    private List<Attempt> runConcurrently(
            Callable<?> firstOperation,
            Callable<?> secondOperation)
            throws Exception {

        var executor =
                Executors.newFixedThreadPool(
                        2);

        var ready =
                new CountDownLatch(
                        2);

        var start =
                new CountDownLatch(
                        1);

        try {
            Future<Attempt> first =
                    executor.submit(
                            concurrentAttempt(
                                    firstOperation,
                                    ready,
                                    start));

            Future<Attempt> second =
                    executor.submit(
                            concurrentAttempt(
                                    secondOperation,
                                    ready,
                                    start));

            assertThat(
                    ready.await(
                            5,
                            TimeUnit.SECONDS))
                    .as("Both Order workers became ready")
                    .isTrue();

            start.countDown();

            return List.of(
                    first.get(
                            10,
                            TimeUnit.SECONDS),
                    second.get(
                            10,
                            TimeUnit.SECONDS));
        } finally {
            start.countDown();

            executor.shutdownNow();

            executor.awaitTermination(
                    5,
                    TimeUnit.SECONDS);
        }
    }

    private Callable<Attempt> concurrentAttempt(
            Callable<?> operation,
            CountDownLatch ready,
            CountDownLatch start) {

        return () -> {

            ready.countDown();

            if (!start.await(
                    5,
                    TimeUnit.SECONDS)) {

                throw new IllegalStateException(
                        "Concurrent Order test start gate timed out.");
            }

            return attempt(
                    operation);
        };
    }

    private Attempt attempt(
            Callable<?> operation) {

        try {
            operation.call();

            return new Attempt(
                    null);
        } catch (Throwable failure) {
            return new Attempt(
                    failure);
        }
    }

    // ---------------------------------------------------------------------
    // Database fixtures
    // ---------------------------------------------------------------------

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
                    'Inventory concurrency fixture product',
                    'inventory_concurrency_fixture_product',
                    NULL,
                    'ACTIVE'
                )
                """,
                TENANT_ID,
                PRODUCT_ID);

        insertActiveCatalogVariant(
                VARIANT_A,
                "CONCURRENCY-VARIANT-A");

        insertActiveCatalogVariant(
                VARIANT_B,
                "CONCURRENCY-VARIANT-B");
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

    private void dropSyntheticDatabaseArtifacts() {

        dropSyntheticDelayTrigger();

        jdbcTemplate.execute("""
                ALTER TABLE orders.orders
                DROP CONSTRAINT IF EXISTS
                    ck_test_force_order_failure
                """);

        jdbcTemplate.execute("""
                ALTER TABLE inventory.inventory_commitments
                DROP CONSTRAINT IF EXISTS
                    ck_test_force_inventory_failure
                """);
    }

    private void dropSyntheticDelayTrigger() {

        jdbcTemplate.execute("""
                DROP TRIGGER IF EXISTS
                    oh011_test_pause_position_update
                ON inventory.inventory_positions
                """);

        jdbcTemplate.execute("""
                DROP FUNCTION IF EXISTS
                    inventory.oh011_test_pause_position_update()
                """);
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

    private static CreateOrderCommand command(
            UUID customerId,
            List<CreateOrderCommand.Item> items) {

        return new CreateOrderCommand(
                TENANT_ID,
                customerId,
                items,
                CreateOrderIdempotencyKeyDigest.of(new byte[32]));
    }

    private static CreateOrderCommand.Item item(
            UUID variantId,
            int quantity) {

        return new CreateOrderCommand.Item(
                variantId,
                quantity);
    }

    private record Attempt(
            Throwable failure) {
    }
}

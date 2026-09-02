package io.github.piresrenan.orderhub.orders;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.sql.SQLException;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.LockSupport;

import javax.sql.DataSource;

import org.assertj.core.api.SoftAssertions;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderCommand;
import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderIdempotencyKeyDigest;
import io.github.piresrenan.orderhub.orders.application.port.in.CreateOrderUseCase;
import io.github.piresrenan.orderhub.support.PostgreSqlTestConfiguration;

/**
 * Adversarial RED proof for OH-011 Catalog lifecycle concurrency.
 *
 * <p>The barriers in this test are PostgreSQL lock state observed through
 * pg_stat_activity. Polling deadlines only prevent an indefinitely hung test;
 * elapsed time is not used as evidence that an invariant holds.</p>
 */
@SpringBootTest(
        properties =
                "orderhub.orders.transaction.timeout=5s")
@Import(PostgreSqlTestConfiguration.class)
class CreateOrderCatalogLifecycleConcurrencyAcceptanceTest {

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

    private static final long STATE_DETECTION_TIMEOUT_NANOS =
            TimeUnit.SECONDS.toNanos(
                    3);

    private static final long POLL_INTERVAL_NANOS =
            TimeUnit.MILLISECONDS.toNanos(
                    10);

    @Autowired
    private CreateOrderUseCase createOrderUseCase;

    @Autowired
    private JdbcTemplate jdbcTemplate;

    @Autowired
    private DataSource dataSource;

    @BeforeEach
    void cleanBusinessState() {

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
                    orders.order_items,
                    orders.orders
                """);
    }

    /**
     * A lifecycle mutation owns the Catalog row before Order begins.
     *
     * <p>Correct behavior: the future Catalog FOR SHARE waits, PostgreSQL then
     * evaluates the committed non-ACTIVE row, and Order fails closed with zero
     * durable effects.</p>
     */
    @ParameterizedTest
    @EnumSource(LifecycleTarget.class)
    void lifecycleChangeThatWinsBeforeCatalogLockMakesWaitingOrderFailClosed(
            LifecycleTarget target)
            throws Exception {

        prepareActiveCatalogAndInventory();

        var executor =
                Executors.newSingleThreadExecutor();

        Attempt attempt = null;
        FirstRaceObservation observation =
                FirstRaceObservation.NO_DECISIVE_STATE;

        try (var lifecycleConnection =
                dataSource.getConnection()) {

            lifecycleConnection.setAutoCommit(
                    false);

            setApplicationName(
                    lifecycleConnection,
                    "oh011_p1b_owner_" + target.name().toLowerCase());

            mutateLifecycle(
                    lifecycleConnection,
                    target);

            Future<Attempt> orderFuture =
                    executor.submit(
                            () ->
                                    attempt(() ->
                                            createOrderUseCase.create(
                                                    command())));

            try {
                observation =
                        awaitFirstRaceObservation(
                                orderFuture,
                                target);
            } finally {
                /*
                 * Release the Catalog update even when the observation is
                 * unexpected so the worker cannot remain stranded.
                 */
                lifecycleConnection.commit();
            }

            attempt =
                    orderFuture.get(
                            5,
                            TimeUnit.SECONDS);

        } finally {

            executor.shutdownNow();

            executor.awaitTermination(
                    5,
                    TimeUnit.SECONDS);
        }

        var softly =
                new SoftAssertions();

        softly.assertThat(observation)
                .as(
                        "%s lifecycle owner must make Order wait on Catalog",
                        target)
                .isEqualTo(
                        FirstRaceObservation.ORDER_WAITING_ON_CATALOG);

        softly.assertThat(attempt)
                .as("Order attempt must complete after lifecycle commit")
                .isNotNull();

        if (attempt != null) {
            softly.assertThat(attempt.failure())
                    .as(
                            "%s committed non-ACTIVE state must reject Order",
                            target)
                    .isNotNull();
        }

        softly.assertThat(orderCount())
                .as("rejected Order must leave no durable Order")
                .isZero();

        softly.assertThat(committedQuantity())
                .as("rejected Order must leave Inventory unchanged")
                .isZero();

        softly.assertThat(commitmentCount())
                .as("rejected Order must leave no commitment ledger")
                .isZero();

        softly.assertThat(currentStatus(target))
                .as("lifecycle mutation itself must commit")
                .isEqualTo(
                        target.nonActiveStatus());

        softly.assertAll();
    }

    /**
     * Order reaches Inventory after Catalog eligibility should already have
     * been stabilized.
     *
     * <p>The Inventory row is deliberately held so the Order transaction
     * remains open. A subsequent Catalog lifecycle UPDATE must then wait on
     * the Order-owned Catalog row lock.</p>
     */
    @ParameterizedTest
    @EnumSource(LifecycleTarget.class)
    void orderThatReachedInventoryProtectsCatalogUntilItsTransactionCompletes(
            LifecycleTarget target)
            throws Exception {

        prepareActiveCatalogAndInventory();

        var executor =
                Executors.newFixedThreadPool(
                        2);

        Attempt orderAttempt = null;

        SecondRaceObservation lifecycleObservation =
                SecondRaceObservation.NO_DECISIVE_STATE;

        try (var inventoryGate =
                dataSource.getConnection()) {

            inventoryGate.setAutoCommit(
                    false);

            lockInventoryPosition(
                    inventoryGate);

            Future<Attempt> orderFuture =
                    executor.submit(
                            () ->
                                    attempt(() ->
                                            createOrderUseCase.create(
                                                    command())));

            var inventoryWaitObserved =
                    awaitCondition(
                            this::isOrderWaitingOnInventoryMutation);

            /*
             * This is a prerequisite of the experiment, not the property under
             * review. Without it we would not know that Order is held open at
             * the intended transaction point.
             */
            assertThat(inventoryWaitObserved)
                    .as(
                            "Order must reach the held Inventory UPDATE before "
                                    + "the lifecycle contender starts")
                    .isTrue();

            var lifecycleApplicationName =
                    "oh011_p1b_contender_"
                            + target.name().toLowerCase();

            Future<Void> lifecycleFuture =
                    executor.submit(
                            () -> {
                                executeLifecycleMutation(
                                        target,
                                        lifecycleApplicationName);

                                return null;
                            });

            lifecycleObservation =
                    awaitSecondRaceObservation(
                            lifecycleFuture,
                            lifecycleApplicationName);

            /*
             * Order can now finish. In the corrected implementation this also
             * releases its Product/Variant FOR SHARE locks, allowing the
             * lifecycle UPDATE to continue.
             */
            inventoryGate.rollback();

            orderAttempt =
                    orderFuture.get(
                            5,
                            TimeUnit.SECONDS);

            lifecycleFuture.get(
                    5,
                    TimeUnit.SECONDS);

        } finally {

            /*
             * rollback is harmless when the transaction was already rolled
             * back and prevents a test failure from retaining the gate lock.
             */
            executor.shutdownNow();

            executor.awaitTermination(
                    5,
                    TimeUnit.SECONDS);
        }

        var softly =
                new SoftAssertions();

        softly.assertThat(lifecycleObservation)
                .as(
                        "%s lifecycle UPDATE must wait while accepted Order "
                                + "transaction remains open",
                        target)
                .isEqualTo(
                        SecondRaceObservation.LIFECYCLE_WAITING_ON_CATALOG);

        softly.assertThat(orderAttempt)
                .isNotNull();

        if (orderAttempt != null) {
            softly.assertThat(orderAttempt.failure())
                    .as("Order that won Catalog eligibility must succeed")
                    .isNull();
        }

        softly.assertThat(orderCount())
                .isEqualTo(1);

        softly.assertThat(committedQuantity())
                .isEqualTo(1);

        softly.assertThat(commitmentCount())
                .isEqualTo(1);

        softly.assertThat(currentStatus(target))
                .as("lifecycle mutation proceeds after Order completes")
                .isEqualTo(
                        target.nonActiveStatus());

        softly.assertAll();
    }

    private FirstRaceObservation awaitFirstRaceObservation(
            Future<Attempt> orderFuture,
            LifecycleTarget target) {

        var deadline =
                System.nanoTime()
                        + STATE_DETECTION_TIMEOUT_NANOS;

        while (System.nanoTime() < deadline) {

            if (orderFuture.isDone()) {
                return FirstRaceObservation.ORDER_COMPLETED_WITHOUT_CATALOG_WAIT;
            }

            if (isOrderWaitingOnCatalog(
                    target)) {

                return FirstRaceObservation.ORDER_WAITING_ON_CATALOG;
            }

            LockSupport.parkNanos(
                    POLL_INTERVAL_NANOS);
        }

        return FirstRaceObservation.NO_DECISIVE_STATE;
    }

    private SecondRaceObservation awaitSecondRaceObservation(
            Future<Void> lifecycleFuture,
            String applicationName) {

        var deadline =
                System.nanoTime()
                        + STATE_DETECTION_TIMEOUT_NANOS;

        while (System.nanoTime() < deadline) {

            if (lifecycleFuture.isDone()) {
                return SecondRaceObservation.LIFECYCLE_COMPLETED_WITHOUT_WAIT;
            }

            if (isApplicationWaitingOnLock(
                    applicationName)) {

                return SecondRaceObservation.LIFECYCLE_WAITING_ON_CATALOG;
            }

            LockSupport.parkNanos(
                    POLL_INTERVAL_NANOS);
        }

        return SecondRaceObservation.NO_DECISIVE_STATE;
    }

    private boolean awaitCondition(
            BooleanQuery condition) {

        var deadline =
                System.nanoTime()
                        + STATE_DETECTION_TIMEOUT_NANOS;

        while (System.nanoTime() < deadline) {

            if (condition.test()) {
                return true;
            }

            LockSupport.parkNanos(
                    POLL_INTERVAL_NANOS);
        }

        return false;
    }

    private boolean isOrderWaitingOnCatalog(
            LifecycleTarget target) {

        var relationMarker =
                switch (target) {
                    case VARIANT ->
                            "catalog.product_variants";
                    case PRODUCT ->
                            "catalog.products";
                };

        return Boolean.TRUE.equals(
                jdbcTemplate.queryForObject("""
                        SELECT EXISTS (
                            SELECT 1
                            FROM pg_stat_activity
                            WHERE datname = current_database()
                              AND pid <> pg_backend_pid()
                              AND state = 'active'
                              AND wait_event_type = 'Lock'
                              AND lower(query) LIKE ?
                              AND lower(query) LIKE '%for share%'
                        )
                        """,
                        Boolean.class,
                        "%"
                                + relationMarker
                                + "%"));
    }

    private boolean isOrderWaitingOnInventoryMutation() {

        return Boolean.TRUE.equals(
                jdbcTemplate.queryForObject("""
                        SELECT EXISTS (
                            SELECT 1
                            FROM pg_stat_activity
                            WHERE datname = current_database()
                              AND pid <> pg_backend_pid()
                              AND state = 'active'
                              AND wait_event_type = 'Lock'
                              AND lower(query) LIKE
                                  '%update inventory.inventory_positions%'
                        )
                        """,
                        Boolean.class));
    }

    private boolean isApplicationWaitingOnLock(
            String applicationName) {

        return Boolean.TRUE.equals(
                jdbcTemplate.queryForObject("""
                        SELECT EXISTS (
                            SELECT 1
                            FROM pg_stat_activity
                            WHERE datname = current_database()
                              AND application_name = ?
                              AND state = 'active'
                              AND wait_event_type = 'Lock'
                        )
                        """,
                        Boolean.class,
                        applicationName));
    }

    private void prepareActiveCatalogAndInventory() {

        jdbcTemplate.update("""
                INSERT INTO catalog.products (
                    tenant_id,
                    id,
                    name,
                    slug,
                    description,
                    status
                )
                VALUES (?, ?, 'P1-B Product', 'p1-b-product', NULL, 'ACTIVE')
                """,
                TENANT_ID,
                PRODUCT_ID);

        jdbcTemplate.update("""
                INSERT INTO catalog.product_variants (
                    tenant_id,
                    id,
                    product_id,
                    sku,
                    status
                )
                VALUES (?, ?, ?, 'SKU-P1-B', 'ACTIVE')
                """,
                TENANT_ID,
                VARIANT_ID,
                PRODUCT_ID);

        jdbcTemplate.update("""
                INSERT INTO inventory.tenant_policies (
                    tenant_id,
                    policy
                )
                VALUES (?, 'DENY')
                """,
                TENANT_ID);

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
                TENANT_ID,
                VARIANT_ID);
    }

    private void lockInventoryPosition(
            Connection connection)
            throws SQLException {

        try (var statement =
                connection.prepareStatement("""
                        SELECT variant_id
                        FROM inventory.inventory_positions
                        WHERE tenant_id = ?
                          AND variant_id = ?
                        FOR UPDATE
                        """)) {

            statement.setObject(
                    1,
                    TENANT_ID);

            statement.setObject(
                    2,
                    VARIANT_ID);

            try (var resultSet =
                    statement.executeQuery()) {

                assertThat(resultSet.next())
                        .as("Inventory gate row exists")
                        .isTrue();
            }
        }
    }

    private void executeLifecycleMutation(
            LifecycleTarget target,
            String applicationName)
            throws SQLException {

        try (var connection =
                dataSource.getConnection()) {

            connection.setAutoCommit(
                    false);

            try {
                setApplicationName(
                        connection,
                        applicationName);

                mutateLifecycle(
                        connection,
                        target);

                connection.commit();

            } catch (Throwable failure) {

                connection.rollback();

                throw failure;
            }
        }
    }

    private static void setApplicationName(
            Connection connection,
            String applicationName)
            throws SQLException {

        /*
         * Use set_config() rather than interpolating application_name into SQL.
         * The marker exists only to make lock-state observation deterministic.
         */

        try (var statement =
                connection.prepareStatement(
                        "SELECT set_config('application_name', ?, false)")) {

            statement.setString(
                    1,
                    applicationName);

            statement.execute();
        }
    }

    private void mutateLifecycle(
            Connection connection,
            LifecycleTarget target)
            throws SQLException {

        var sql =
                switch (target) {
                    case VARIANT -> """
                            UPDATE catalog.product_variants
                            SET status = 'INACTIVE'
                            WHERE tenant_id = ?
                              AND id = ?
                            """;
                    case PRODUCT -> """
                            UPDATE catalog.products
                            SET status = 'ARCHIVED'
                            WHERE tenant_id = ?
                              AND id = ?
                            """;
                };

        var targetId =
                switch (target) {
                    case VARIANT ->
                            VARIANT_ID;
                    case PRODUCT ->
                            PRODUCT_ID;
                };

        try (var statement =
                connection.prepareStatement(
                        sql)) {

            statement.setObject(
                    1,
                    TENANT_ID);

            statement.setObject(
                    2,
                    targetId);

            var updated =
                    statement.executeUpdate();

            assertThat(updated)
                    .as("Lifecycle target row exists")
                    .isEqualTo(1);
        }
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

    private static CreateOrderCommand command() {

        return new CreateOrderCommand(
                TENANT_ID,
                CUSTOMER_ID,
                List.of(
                        new CreateOrderCommand.Item(
                                VARIANT_ID,
                                1)),
                CreateOrderIdempotencyKeyDigest.of(new byte[32]));
    }

    private long orderCount() {

        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM orders.orders
                WHERE tenant_id = ?
                """,
                Long.class,
                TENANT_ID);
    }

    private long committedQuantity() {

        return jdbcTemplate.queryForObject("""
                SELECT committed
                FROM inventory.inventory_positions
                WHERE tenant_id = ?
                  AND variant_id = ?
                """,
                Long.class,
                TENANT_ID,
                VARIANT_ID);
    }

    private long commitmentCount() {

        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM inventory.inventory_commitments
                WHERE tenant_id = ?
                  AND variant_id = ?
                """,
                Long.class,
                TENANT_ID,
                VARIANT_ID);
    }

    private String currentStatus(
            LifecycleTarget target) {

        return switch (target) {
            case VARIANT ->
                    jdbcTemplate.queryForObject("""
                            SELECT status
                            FROM catalog.product_variants
                            WHERE tenant_id = ?
                              AND id = ?
                            """,
                            String.class,
                            TENANT_ID,
                            VARIANT_ID);

            case PRODUCT ->
                    jdbcTemplate.queryForObject("""
                            SELECT status
                            FROM catalog.products
                            WHERE tenant_id = ?
                              AND id = ?
                            """,
                            String.class,
                            TENANT_ID,
                            PRODUCT_ID);
        };
    }

    private enum LifecycleTarget {

        VARIANT("INACTIVE"),
        PRODUCT("ARCHIVED");

        private final String nonActiveStatus;

        LifecycleTarget(
                String nonActiveStatus) {

            this.nonActiveStatus =
                    nonActiveStatus;
        }

        String nonActiveStatus() {
            return nonActiveStatus;
        }
    }

    private enum FirstRaceObservation {
        ORDER_WAITING_ON_CATALOG,
        ORDER_COMPLETED_WITHOUT_CATALOG_WAIT,
        NO_DECISIVE_STATE
    }

    private enum SecondRaceObservation {
        LIFECYCLE_WAITING_ON_CATALOG,
        LIFECYCLE_COMPLETED_WITHOUT_WAIT,
        NO_DECISIVE_STATE
    }

    @FunctionalInterface
    private interface BooleanQuery {
        boolean test();
    }

    private record Attempt(
            Throwable failure) {
    }
}

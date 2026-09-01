package io.github.piresrenan.orderhub.orders.adapter.out.persistence.postgresql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;

import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import io.github.piresrenan.orderhub.orders.adapter.out.transaction.spring.SpringTransactionExecutor;
import io.github.piresrenan.orderhub.orders.application.port.out.OrderPersistenceException;
import io.github.piresrenan.orderhub.orders.application.port.out.TransactionExecutor;
import io.github.piresrenan.orderhub.orders.domain.model.Order;
import io.github.piresrenan.orderhub.orders.domain.model.OrderItem;
import io.github.piresrenan.orderhub.orders.domain.model.OrderStatus;

@Testcontainers
class PostgreSqlOrderRepositoryTest {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse(
            "postgres:18.6-trixie@sha256:"
                    + "4ef4dbc939d61acea57712655ddb4b4ab27419c913f94cca0cd57cb3ea3c2280")
            .asCompatibleSubstituteFor("postgres");

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(POSTGRES_IMAGE)
            .withDatabaseName("orderhub_test")
            .withUsername("orderhub_test")
            .withPassword("synthetic-test-password");

    private static JdbcTemplate jdbcTemplate;
    private static TransactionExecutor transactionExecutor;

    /**
     * Creates the real PostgreSQL-backed JDBC and transaction infrastructure used
     * by repository integration tests after applying the production Flyway schema.
     */
    @BeforeAll
    static void configurePersistence() {
        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword());

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        jdbcTemplate = new JdbcTemplate(dataSource);
        transactionExecutor =
                new SpringTransactionExecutor(
                        new TransactionTemplate(
                                new DataSourceTransactionManager(
                                        dataSource)));
    }

    /**
     * Removes persisted business state before each scenario while preserving the
     * migrated database structure.
     */
    @BeforeEach
    void cleanBusinessData() {
        jdbcTemplate.update("""
                TRUNCATE TABLE
                    orders.order_items,
                    orders.orders
                """);
    }

    @Test
    void savesAndReloadsCompleteOrderWithinTenant() {
        // Why: durable persistence must round-trip the complete aggregate rather
        // than only the Order root or an infrastructure-specific representation.
        // Covers: root persistence, item persistence, tenant-scoped lookup,
        // line ordering and explicit domain rehydration from PostgreSQL.
        // Prevents: partial aggregate loading, item loss/reordering and repositories
        // reconstructing existing Orders through new-order creation semantics.

        var tenantId = UUID.randomUUID();
        var orderId = UUID.randomUUID();
        var customerId = UUID.randomUUID();
        var firstProductId = UUID.randomUUID();
        var secondProductId = UUID.randomUUID();

        var order = Order.create(
                orderId,
                tenantId,
                customerId,
                List.of(
                        new OrderItem(
                                firstProductId,
                                2),
                        new OrderItem(
                                secondProductId,
                                4)));

        var writer = new PostgreSqlOrderRepository(jdbcTemplate);

        writer.save(order);

        // A separate adapter instance ensures the assertion exercises persisted
        // state rather than relying on repository-instance memory.
        var reader = new PostgreSqlOrderRepository(jdbcTemplate);

        var result = reader.findById(
                tenantId,
                orderId);

        assertThat(result)
                .isPresent();

        var rehydrated = result.orElseThrow();

        assertThat(rehydrated.id())
                .isEqualTo(orderId);

        assertThat(rehydrated.tenantId())
                .isEqualTo(tenantId);

        assertThat(rehydrated.customerId())
                .isEqualTo(customerId);

        assertThat(rehydrated.status())
                .isEqualTo(OrderStatus.CREATED);

        assertThat(rehydrated.items())
                .extracting(OrderItem::productId)
                .containsExactly(
                        firstProductId,
                        secondProductId);

        assertThat(rehydrated.items())
                .extracting(OrderItem::quantity)
                .containsExactly(
                        2,
                        4);
    }

    @Test
    void doesNotReturnOrderOwnedByDifferentTenant() {
        // Why: repository lookup is explicitly tenant-scoped and must never expose an
        // aggregate belonging to another tenant even when the Order UUID is known.
        // Covers: PostgreSQL tenant predicate applied to root lookup.
        // Prevents: cross-tenant data disclosure caused by querying only by order id.

        var owningTenantId = UUID.randomUUID();
        var requestingTenantId = UUID.randomUUID();
        var orderId = UUID.randomUUID();

        var order = Order.create(
                orderId,
                owningTenantId,
                UUID.randomUUID(),
                List.of(
                        new OrderItem(
                                UUID.randomUUID(),
                                2)));

        var repository = new PostgreSqlOrderRepository(jdbcTemplate);

        repository.save(order);

        assertThat(repository.findById(
                requestingTenantId,
                orderId))
                .isEmpty();
    }

    @Test
    void returnsEmptyWhenOrderDoesNotExist() {
        // Why: absence is part of the repository contract and must not be translated
        // into null, an exception or a fabricated aggregate.
        // Covers: tenant-scoped lookup when no relational root exists.
        // Prevents: ambiguous absence semantics leaking from JDBC into the application.

        var repository = new PostgreSqlOrderRepository(jdbcTemplate);

        assertThat(repository.findById(
                UUID.randomUUID(),
                UUID.randomUUID()))
                .isEmpty();
    }

    @Test
    void reloadsItemsAccordingToPersistedLineNumber() {
        // Why: SQL row order is not an aggregate-order guarantee.
        // Covers: reconstruction according to persisted line_number rather than item
        // insertion order.
        // Prevents: nondeterministic Order item ordering after persistence reload.

        var tenantId = UUID.randomUUID();
        var orderId = UUID.randomUUID();
        var customerId = UUID.randomUUID();

        var firstProductId = UUID.randomUUID();
        var secondProductId = UUID.randomUUID();
        var thirdProductId = UUID.randomUUID();

        jdbcTemplate.update("""
                INSERT INTO orders.orders (
                    tenant_id,
                    id,
                    customer_id,
                    status
                )
                VALUES (?, ?, ?, ?)
                """,
                tenantId,
                orderId,
                customerId,
                OrderStatus.CREATED.name());

        // Intentionally insert relational rows outside aggregate order.
        jdbcTemplate.update("""
                INSERT INTO orders.order_items (
                    tenant_id,
                    order_id,
                    line_number,
                    product_id,
                    quantity
                )
                VALUES (?, ?, ?, ?, ?)
                """,
                tenantId,
                orderId,
                2,
                thirdProductId,
                3);

        jdbcTemplate.update("""
                INSERT INTO orders.order_items (
                    tenant_id,
                    order_id,
                    line_number,
                    product_id,
                    quantity
                )
                VALUES (?, ?, ?, ?, ?)
                """,
                tenantId,
                orderId,
                0,
                firstProductId,
                1);

        jdbcTemplate.update("""
                INSERT INTO orders.order_items (
                    tenant_id,
                    order_id,
                    line_number,
                    product_id,
                    quantity
                )
                VALUES (?, ?, ?, ?, ?)
                """,
                tenantId,
                orderId,
                1,
                secondProductId,
                2);

        var repository = new PostgreSqlOrderRepository(jdbcTemplate);

        var order = repository.findById(
                tenantId,
                orderId)
                .orElseThrow();

        assertThat(order.items())
                .extracting(OrderItem::productId)
                .containsExactly(
                        firstProductId,
                        secondProductId,
                        thirdProductId);

        assertThat(order.items())
                .extracting(OrderItem::quantity)
                .containsExactly(
                        1,
                        2,
                        3);
    }

    @Test
    void callerOwnedTransactionRollsBackCompleteAggregateWhenLaterItemInsertFails() {
        // Why: OrderRepository.save represents persistence of one complete aggregate,
        // so failure after some SQL statements have succeeded must not leave partial
        // relational state.
        // Covers: transaction rollback after root and an earlier item were written.
        // Prevents: orphaned or partially persisted Orders after database failures.

        var tenantId = UUID.randomUUID();
        var orderId = UUID.randomUUID();

        var order = Order.create(
                orderId,
                tenantId,
                UUID.randomUUID(),
                List.of(
                        new OrderItem(
                                UUID.randomUUID(),
                                1),
                        new OrderItem(
                                UUID.randomUUID(),
                                2)));

        // Test-only database constraint creates a genuine failure on the second item
        // without adding any production test hook to the repository implementation.
        jdbcTemplate.execute("""
                ALTER TABLE orders.order_items
                ADD CONSTRAINT ck_order_items_test_reject_second_line
                CHECK (line_number <> 1)
                """);

        try {
            var repository = new PostgreSqlOrderRepository(jdbcTemplate);

            assertThatThrownBy(() ->
                    transactionExecutor.execute(
                            () -> repository.save(order)))
                    .isInstanceOf(OrderPersistenceException.class)
                    .hasCauseInstanceOf(DataIntegrityViolationException.class);

            var persistedRoots = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*)
                    FROM orders.orders
                    WHERE tenant_id = ?
                      AND id = ?
                    """,
                    Long.class,
                    tenantId,
                    orderId);

            var persistedItems = jdbcTemplate.queryForObject("""
                    SELECT COUNT(*)
                    FROM orders.order_items
                    WHERE tenant_id = ?
                      AND order_id = ?
                    """,
                    Long.class,
                    tenantId,
                    orderId);

            assertThat(persistedRoots)
                    .isZero();

            assertThat(persistedItems)
                    .isZero();
        } finally {
            jdbcTemplate.execute("""
                    ALTER TABLE orders.order_items
                    DROP CONSTRAINT IF EXISTS ck_order_items_test_reject_second_line
                    """);
        }
    }

}

package io.github.piresrenan.orderhub.orders.adapter.out.persistence.postgresql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Verifies the relational integrity guarantees established by the Orders
 * PostgreSQL schema.
 *
 * <p>These tests intentionally operate directly through JDBC rather than the
 * future repository adapter. This isolates database constraints from
 * application and persistence-mapping behavior.</p>
 */
@Testcontainers
class PostgreSqlSchemaConstraintsTest {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse(
            "postgres:18.6-trixie@sha256:"
                    + "4ef4dbc939d61acea57712655ddb4b4ab27419c913f94cca0cd57cb3ea3c2280")
            .asCompatibleSubstituteFor("postgres");

    private static final UUID TENANT_A = UUID.fromString(
            "11111111-1111-1111-1111-111111111111");

    private static final UUID TENANT_B = UUID.fromString(
            "22222222-2222-2222-2222-222222222222");

    private static final UUID ORDER_ID = UUID.fromString(
            "33333333-3333-3333-3333-333333333333");

    private static final UUID CUSTOMER_A = UUID.fromString(
            "44444444-4444-4444-4444-444444444444");

    private static final UUID CUSTOMER_B = UUID.fromString(
            "55555555-5555-5555-5555-555555555555");

    private static final UUID VARIANT_A = UUID.fromString(
            "66666666-6666-6666-6666-666666666666");

    private static final UUID VARIANT_B = UUID.fromString(
            "77777777-7777-7777-7777-777777777777");

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(POSTGRES_IMAGE)
                    .withDatabaseName("orderhub_test")
                    .withUsername("orderhub_test")
                    .withPassword("synthetic-test-password");

    private static JdbcTemplate jdbcTemplate;

    /**
     * Migrates the disposable PostgreSQL database once before relational
     * constraint tests execute.
     *
     * <p>The resulting schema must be produced exclusively by the same Flyway
     * migrations used by the application.</p>
     */
    @BeforeAll
    static void migrateSchema() {
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
    }

    /**
     * Removes business rows before each test while preserving the migrated
     * schema and Flyway history.
     *
     * <p>This guarantees that one relational scenario cannot influence another
     * through data left behind by a previous test.</p>
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
    void allowsSameOrderIdentifierAcrossDifferentTenants() {
        // Why: an aggregate UUID must not become a global tenant boundary by accident.
        // Covers: the composite primary key (tenant_id, id) on orders.orders.
        // Prevents: one tenant being unable to persist an otherwise valid identifier
        // merely because another tenant already uses that UUID.

        insertOrder(TENANT_A, ORDER_ID, CUSTOMER_A, "CREATED");
        insertOrder(TENANT_B, ORDER_ID, CUSTOMER_B, "CREATED");

        var count = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM orders.orders
                WHERE id = ?
                """,
                Integer.class,
                ORDER_ID);

        assertThat(count)
                .isEqualTo(2);
    }

    @Test
    void rejectsDuplicateOrderIdentityWithinSameTenant() {
        // Why: one tenant must not persist two roots with the same aggregate identity.
        // Covers: composite primary-key uniqueness on (tenant_id, id).
        // Prevents: ambiguous aggregate ownership inside one tenant.

        insertOrder(TENANT_A, ORDER_ID, CUSTOMER_A, "CREATED");

        assertThatThrownBy(() ->
                insertOrder(TENANT_A, ORDER_ID, CUSTOMER_B, "CREATED"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsUnsupportedOrderStatus() {
        // Why: persisted lifecycle state must remain compatible with the domain model.
        // Covers: ck_orders_status.
        // Prevents: unsupported states entering storage through SQL or future defects.

        assertThatThrownBy(() ->
                insertOrder(TENANT_A, ORDER_ID, CUSTOMER_A, "PAID"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsZeroItemQuantity() {
        // Why: zero quantity is invalid domain state and storage must defend the same
        // invariant.
        // Covers: ck_order_items_quantity at its lower boundary.
        // Prevents: bypassing domain validation through direct persistence paths.

        insertOrder(TENANT_A, ORDER_ID, CUSTOMER_A, "CREATED");

        assertThatThrownBy(() ->
                insertItem(TENANT_A, ORDER_ID, 0, VARIANT_A, 0))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsNegativeItemQuantity() {
        // Why: negative quantities must never represent a persisted Order line.
        // Covers: ck_order_items_quantity below its valid range.
        // Prevents: corrupted quantity state entering Orders storage.

        insertOrder(TENANT_A, ORDER_ID, CUSTOMER_A, "CREATED");

        assertThatThrownBy(() ->
                insertItem(TENANT_A, ORDER_ID, 0, VARIANT_A, -1))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsNegativeLineNumber() {
        // Why: persisted list positions cannot be negative.
        // Covers: ck_order_items_line_number.
        // Prevents: impossible ordering state during aggregate reconstruction.

        insertOrder(TENANT_A, ORDER_ID, CUSTOMER_A, "CREATED");

        assertThatThrownBy(() ->
                insertItem(TENANT_A, ORDER_ID, -1, VARIANT_A, 1))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsItemWithoutOwningOrder() {
        // Why: an Order item has no independent lifecycle outside its aggregate root.
        // Covers: fk_order_items_order.
        // Prevents: orphan child rows surviving without an owning Order.

        assertThatThrownBy(() ->
                insertItem(TENANT_A, ORDER_ID, 0, VARIANT_A, 1))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsItemOwnedByDifferentTenant() {
        // Why: an item must belong to an Order inside the same tenant boundary.
        // Covers: the composite tenant-aware foreign key.
        // Prevents: cross-tenant child ownership even when Order UUIDs coincide.

        insertOrder(TENANT_A, ORDER_ID, CUSTOMER_A, "CREATED");

        assertThatThrownBy(() ->
                insertItem(TENANT_B, ORDER_ID, 0, VARIANT_A, 1))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void rejectsDuplicateLinePositionWithinSameOrder() {
        // Why: each persisted list position must identify exactly one Order line.
        // Covers: composite order_items primary key.
        // Prevents: ambiguous ordering during aggregate reconstruction.

        insertOrder(TENANT_A, ORDER_ID, CUSTOMER_A, "CREATED");
        insertItem(TENANT_A, ORDER_ID, 0, VARIANT_A, 1);

        assertThatThrownBy(() ->
                insertItem(TENANT_A, ORDER_ID, 0, VARIANT_B, 1))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    /**
     * Inserts one Order root directly through JDBC so schema tests exercise
     * PostgreSQL independently of the future repository adapter.
     *
     * @param tenantId owning tenant identifier
     * @param orderId aggregate identifier
     * @param customerId synthetic customer identifier
     * @param status persisted Order status
     */
    private void insertOrder(
            UUID tenantId,
            UUID orderId,
            UUID customerId,
            String status) {

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
                status);
    }

    /**
     * Inserts one Order item directly through JDBC so ownership, identity and
     * value constraints are exercised without repository behavior masking them.
     *
     * @param tenantId owning tenant identifier
     * @param orderId owning Order identifier
     * @param lineNumber persisted list position
     * @param variantId synthetic variant identifier
     * @param quantity requested positive quantity
     */
    private void insertItem(
            UUID tenantId,
            UUID orderId,
            int lineNumber,
            UUID variantId,
            int quantity) {

        jdbcTemplate.update("""
                INSERT INTO orders.order_items (
                    tenant_id,
                    order_id,
                    line_number,
                    variant_id,
                    quantity
                )
                VALUES (?, ?, ?, ?, ?)
                """,
                tenantId,
                orderId,
                lineNumber,
                variantId,
                quantity);
    }
}
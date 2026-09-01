package io.github.piresrenan.orderhub.inventory.adapter.out.persistence.postgresql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Assumptions;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Proves Inventory relational invariants directly against PostgreSQL.
 *
 * <p>
 * Tests deliberately bypass future Inventory repositories. PostgreSQL must
 * defend impossible durable state even when application/domain validation is
 * absent.
 * </p>
 */
@Testcontainers
class PostgreSqlInventorySchemaConstraintsTest {

    private static final DockerImageName POSTGRES_IMAGE =
            DockerImageName.parse(
                    "postgres:18.6-trixie@sha256:"
                            + "4ef4dbc939d61acea57712655ddb4b4ab27419c913f94cca0cd57cb3ea3c2280")
                    .asCompatibleSubstituteFor("postgres");

    private static final UUID TENANT_A =
            UUID.fromString(
                    "11111111-1111-1111-1111-111111111111");

    private static final UUID TENANT_B =
            UUID.fromString(
                    "22222222-2222-2222-2222-222222222222");

    private static final UUID VARIANT_ID =
            UUID.fromString(
                    "33333333-3333-3333-3333-333333333333");

    private static final UUID ORDER_ID =
            UUID.fromString(
                    "44444444-4444-4444-4444-444444444444");

    private static final UUID COMMITMENT_ID =
            UUID.fromString(
                    "55555555-5555-5555-5555-555555555555");

    private static final OffsetDateTime CREATED_AT =
            OffsetDateTime.of(
                    2026,
                    9,
                    1,
                    9,
                    0,
                    0,
                    123_456_000,
                    ZoneOffset.UTC);

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(POSTGRES_IMAGE)
                    .withDatabaseName("orderhub_test")
                    .withUsername("orderhub_test")
                    .withPassword("synthetic-test-password");

    private static JdbcTemplate jdbcTemplate;

    @BeforeAll
    static void migrateSchema() {

        var dataSource =
                new DriverManagerDataSource(
                        POSTGRES.getJdbcUrl(),
                        POSTGRES.getUsername(),
                        POSTGRES.getPassword());

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        jdbcTemplate =
                new JdbcTemplate(dataSource);
    }

    // -------------------------------------------------------------------------
    // Structural gate
    // -------------------------------------------------------------------------

    @Test
    void createsInventorySchemaAndOwnedTables() {

        var schemaCount =
                jdbcTemplate.queryForObject("""
                        SELECT COUNT(*)
                        FROM information_schema.schemata
                        WHERE schema_name = 'inventory'
                        """,
                        Integer.class);

        var tableCount =
                jdbcTemplate.queryForObject("""
                        SELECT COUNT(*)
                        FROM information_schema.tables
                        WHERE table_schema = 'inventory'
                          AND table_name IN (
                              'tenant_policies',
                              'inventory_positions',
                              'inventory_commitments'
                          )
                        """,
                        Integer.class);

        assertThat(schemaCount)
                .isEqualTo(1);

        assertThat(tableCount)
                .isEqualTo(3);
    }

    @Nested
    class Constraints {

        @BeforeEach
        void requireInventoryStructureAndCleanData() {

            Assumptions.assumeTrue(
                    inventoryStructureExists(),
                    "V7 Inventory schema does not exist yet.");

            jdbcTemplate.update("""
                    TRUNCATE TABLE
                        inventory.inventory_commitments,
                        inventory.inventory_positions,
                        inventory.tenant_policies
                    """);
        }

        // ---------------------------------------------------------------------
        // Tenant policy
        // ---------------------------------------------------------------------

        @Test
        void storesSupportedInventoryPolicies() {

            insertPolicy(
                    TENANT_A,
                    "DENY");

            insertPolicy(
                    TENANT_B,
                    "ALLOW_BACKORDER");

            var count =
                    jdbcTemplate.queryForObject("""
                            SELECT COUNT(*)
                            FROM inventory.tenant_policies
                            """,
                            Integer.class);

            assertThat(count)
                    .isEqualTo(2);
        }

        @Test
        void rejectsDuplicatePolicyForSameTenant() {

            insertPolicy(
                    TENANT_A,
                    "DENY");

            assertThatThrownBy(() ->
                    insertPolicy(
                            TENANT_A,
                            "ALLOW_BACKORDER"))
                    .satisfies(exception ->
                            assertPostgresFailure(
                                    exception,
                                    "23505",
                                    "pk_inventory_tenant_policies"));
        }

        @Test
        void rejectsUnsupportedInventoryPolicy() {

            assertThatThrownBy(() ->
                    insertPolicy(
                            TENANT_A,
                            "UNLIMITED_OVERSELL"))
                    .satisfies(exception ->
                            assertPostgresFailure(
                                    exception,
                                    "23514",
                                    "ck_inventory_tenant_policies_policy"));
        }

        @Test
        void rejectsPolicyWithoutTenantIdentity() {

            assertThatThrownBy(() ->
                    insertPolicy(
                            null,
                            "DENY"))
                    .satisfies(exception ->
                            assertPostgresFailure(
                                    exception,
                                    "23502",
                                    null));
        }

        // ---------------------------------------------------------------------
        // Position
        // ---------------------------------------------------------------------

        @Test
        void allowsSameVariantIdentityAcrossDifferentTenants() {

            insertPosition(
                    TENANT_A,
                    VARIANT_ID,
                    10,
                    0,
                    0,
                    0);

            insertPosition(
                    TENANT_B,
                    VARIANT_ID,
                    20,
                    0,
                    0,
                    0);
        }

        @Test
        void rejectsDuplicatePositionWithinTenant() {

            insertPosition(
                    TENANT_A,
                    VARIANT_ID,
                    10,
                    0,
                    0,
                    0);

            assertThatThrownBy(() ->
                    insertPosition(
                            TENANT_A,
                            VARIANT_ID,
                            20,
                            0,
                            0,
                            0))
                    .satisfies(exception ->
                            assertPostgresFailure(
                                    exception,
                                    "23505",
                                    "pk_inventory_positions"));
        }

        @Test
        void rejectsPositionWithoutVariantIdentity() {

            assertThatThrownBy(() ->
                    insertPosition(
                            TENANT_A,
                            null,
                            10,
                            0,
                            0,
                            0))
                    .satisfies(exception ->
                            assertPostgresFailure(
                                    exception,
                                    "23502",
                                    null));
        }

        @Test
        void rejectsNegativeOnHandQuantity() {

            assertThatThrownBy(() ->
                    insertPosition(
                            TENANT_A,
                            VARIANT_ID,
                            -1,
                            0,
                            0,
                            0))
                    .satisfies(exception ->
                            assertPostgresFailure(
                                    exception,
                                    "23514",
                                    null));
        }

        @Test
        void rejectsNegativeCommittedQuantity() {

            assertThatThrownBy(() ->
                    insertPosition(
                            TENANT_A,
                            VARIANT_ID,
                            10,
                            -1,
                            0,
                            0))
                    .satisfies(exception ->
                            assertPostgresFailure(
                                    exception,
                                    "23514",
                                    "ck_inventory_positions_committed_non_negative"));
        }

        @Test
        void rejectsNegativePositionBackorderedQuantity() {

            assertThatThrownBy(() ->
                    insertPosition(
                            TENANT_A,
                            VARIANT_ID,
                            10,
                            0,
                            -1,
                            0))
                    .satisfies(exception ->
                            assertPostgresFailure(
                                    exception,
                                    "23514",
                                    "ck_inventory_positions_backordered_non_negative"));
        }

        @Test
        void rejectsNegativeSafetyStock() {

            assertThatThrownBy(() ->
                    insertPosition(
                            TENANT_A,
                            VARIANT_ID,
                            10,
                            0,
                            0,
                            -1))
                    .satisfies(exception ->
                            assertPostgresFailure(
                                    exception,
                                    "23514",
                                    "ck_inventory_positions_safety_stock_non_negative"));
        }

        @Test
        void rejectsCommittedQuantityAboveOnHand() {

            assertThatThrownBy(() ->
                    insertPosition(
                            TENANT_A,
                            VARIANT_ID,
                            10,
                            11,
                            0,
                            0))
                    .satisfies(exception ->
                            assertPostgresFailure(
                                    exception,
                                    "23514",
                                    "ck_inventory_positions_committed_not_above_on_hand"));
        }

        @Test
        void allowsSafetyStockToMakeAvailableToPromiseNegative() {

            insertPosition(
                    TENANT_A,
                    VARIANT_ID,
                    10,
                    8,
                    0,
                    5);

            var availableToPromise =
                    jdbcTemplate.queryForObject("""
                            SELECT on_hand - committed - safety_stock
                            FROM inventory.inventory_positions
                            WHERE tenant_id = ?
                              AND variant_id = ?
                            """,
                            Long.class,
                            TENANT_A,
                            VARIANT_ID);

            assertThat(availableToPromise)
                    .isEqualTo(-3L);
        }

        @Test
        void supportsBigintInventoryAccumulatorBoundary() {

            insertPosition(
                    TENANT_A,
                    VARIANT_ID,
                    Long.MAX_VALUE,
                    Long.MAX_VALUE,
                    Long.MAX_VALUE,
                    0);

            var backordered =
                    jdbcTemplate.queryForObject("""
                            SELECT backordered
                            FROM inventory.inventory_positions
                            WHERE tenant_id = ?
                              AND variant_id = ?
                            """,
                            Long.class,
                            TENANT_A,
                            VARIANT_ID);

            assertThat(backordered)
                    .isEqualTo(Long.MAX_VALUE);
        }

        // ---------------------------------------------------------------------
        // Commitment ledger
        // ---------------------------------------------------------------------

        @Test
        void storesFullyAllocatedCommitment() {

            insertCommitment(
                    TENANT_A,
                    COMMITMENT_ID,
                    ORDER_ID,
                    VARIANT_ID,
                    5,
                    5,
                    0,
                    CREATED_AT);
        }

        @Test
        void storesPartiallyBackorderedCommitment() {

            insertCommitment(
                    TENANT_A,
                    COMMITMENT_ID,
                    ORDER_ID,
                    VARIANT_ID,
                    8,
                    5,
                    3,
                    CREATED_AT);
        }

        @Test
        void storesFullyBackorderedCommitment() {

            insertCommitment(
                    TENANT_A,
                    COMMITMENT_ID,
                    ORDER_ID,
                    VARIANT_ID,
                    4,
                    0,
                    4,
                    CREATED_AT);
        }

        @Test
        void rejectsDuplicateCommitmentIdentityWithinTenant() {

            insertCommitment(
                    TENANT_A,
                    COMMITMENT_ID,
                    ORDER_ID,
                    VARIANT_ID,
                    1,
                    1,
                    0,
                    CREATED_AT);

            assertThatThrownBy(() ->
                    insertCommitment(
                            TENANT_A,
                            COMMITMENT_ID,
                            UUID.randomUUID(),
                            UUID.randomUUID(),
                            1,
                            1,
                            0,
                            CREATED_AT))
                    .satisfies(exception ->
                            assertPostgresFailure(
                                    exception,
                                    "23505",
                                    "pk_inventory_commitments"));
        }

        @Test
        void allowsSameCommitmentIdentityAcrossDifferentTenants() {

            insertCommitment(
                    TENANT_A,
                    COMMITMENT_ID,
                    ORDER_ID,
                    VARIANT_ID,
                    1,
                    1,
                    0,
                    CREATED_AT);

            insertCommitment(
                    TENANT_B,
                    COMMITMENT_ID,
                    ORDER_ID,
                    VARIANT_ID,
                    1,
                    1,
                    0,
                    CREATED_AT);
        }

        @Test
        void rejectsDuplicateOrderVariantCommitmentWithinTenant() {

            insertCommitment(
                    TENANT_A,
                    COMMITMENT_ID,
                    ORDER_ID,
                    VARIANT_ID,
                    1,
                    1,
                    0,
                    CREATED_AT);

            assertThatThrownBy(() ->
                    insertCommitment(
                            TENANT_A,
                            UUID.randomUUID(),
                            ORDER_ID,
                            VARIANT_ID,
                            1,
                            1,
                            0,
                            CREATED_AT))
                    .satisfies(exception ->
                            assertPostgresFailure(
                                    exception,
                                    "23505",
                                    "uq_inventory_commitments_tenant_order_variant"));
        }

        @Test
        void allowsSameOrderVariantCommitmentAcrossDifferentTenants() {

            insertCommitment(
                    TENANT_A,
                    UUID.randomUUID(),
                    ORDER_ID,
                    VARIANT_ID,
                    1,
                    1,
                    0,
                    CREATED_AT);

            insertCommitment(
                    TENANT_B,
                    UUID.randomUUID(),
                    ORDER_ID,
                    VARIANT_ID,
                    1,
                    1,
                    0,
                    CREATED_AT);
        }

        @Test
        void rejectsCommitmentWithoutOrderIdentity() {

            assertThatThrownBy(() ->
                    insertCommitment(
                            TENANT_A,
                            COMMITMENT_ID,
                            null,
                            VARIANT_ID,
                            1,
                            1,
                            0,
                            CREATED_AT))
                    .satisfies(exception ->
                            assertPostgresFailure(
                                    exception,
                                    "23502",
                                    null));
        }

        @Test
        void rejectsCommitmentWithoutVariantIdentity() {

            assertThatThrownBy(() ->
                    insertCommitment(
                            TENANT_A,
                            COMMITMENT_ID,
                            ORDER_ID,
                            null,
                            1,
                            1,
                            0,
                            CREATED_AT))
                    .satisfies(exception ->
                            assertPostgresFailure(
                                    exception,
                                    "23502",
                                    null));
        }

        @Test
        void rejectsNonPositiveRequestedQuantity() {

            assertThatThrownBy(() ->
                    insertCommitment(
                            TENANT_A,
                            COMMITMENT_ID,
                            ORDER_ID,
                            VARIANT_ID,
                            0,
                            0,
                            0,
                            CREATED_AT))
                    .satisfies(exception ->
                            assertPostgresFailure(
                                    exception,
                                    "23514",
                                    "ck_inventory_commitments_requested_positive"));
        }

        @Test
        void rejectsNegativeAllocatedQuantity() {

            assertThatThrownBy(() ->
                    insertCommitment(
                            TENANT_A,
                            COMMITMENT_ID,
                            ORDER_ID,
                            VARIANT_ID,
                            1,
                            -1,
                            2,
                            CREATED_AT))
                    .satisfies(exception ->
                            assertPostgresFailure(
                                    exception,
                                    "23514",
                                    "ck_inventory_commitments_allocated_non_negative"));
        }

        @Test
        void rejectsNegativeCommitmentBackorderedQuantity() {

            assertThatThrownBy(() ->
                    insertCommitment(
                            TENANT_A,
                            COMMITMENT_ID,
                            ORDER_ID,
                            VARIANT_ID,
                            1,
                            1,
                            -1,
                            CREATED_AT))
                    .satisfies(exception ->
                            assertPostgresFailure(
                                    exception,
                                    "23514",
                                    "ck_inventory_commitments_backordered_non_negative"));
        }

        @Test
        void rejectsCommitmentWhoseQuantitiesDoNotReconcile() {

            assertThatThrownBy(() ->
                    insertCommitment(
                            TENANT_A,
                            COMMITMENT_ID,
                            ORDER_ID,
                            VARIANT_ID,
                            10,
                            6,
                            3,
                            CREATED_AT))
                    .satisfies(exception ->
                            assertPostgresFailure(
                                    exception,
                                    "23514",
                                    "ck_inventory_commitments_reconciled"));
        }

        @Test
        void rejectsAllocatedQuantityAboveRequestedQuantity() {

            assertThatThrownBy(() ->
                    insertCommitment(
                            TENANT_A,
                            COMMITMENT_ID,
                            ORDER_ID,
                            VARIANT_ID,
                            10,
                            11,
                            0,
                            CREATED_AT))
                    .satisfies(exception ->
                            assertPostgresFailure(
                                    exception,
                                    "23514",
                                    "ck_inventory_commitments_reconciled"));
        }

        @Test
        void rejectsCommitmentWithoutCreationTime() {

            assertThatThrownBy(() ->
                    insertCommitment(
                            TENANT_A,
                            COMMITMENT_ID,
                            ORDER_ID,
                            VARIANT_ID,
                            1,
                            1,
                            0,
                            null))
                    .satisfies(exception ->
                            assertPostgresFailure(
                                    exception,
                                    "23502",
                                    null));
        }

        @Test
        void preservesMicrosecondCommitmentCreationTime() {

            insertCommitment(
                    TENANT_A,
                    COMMITMENT_ID,
                    ORDER_ID,
                    VARIANT_ID,
                    1,
                    1,
                    0,
                    CREATED_AT);

            var persisted =
                    jdbcTemplate.queryForObject("""
                            SELECT created_at
                            FROM inventory.inventory_commitments
                            WHERE tenant_id = ?
                              AND commitment_id = ?
                            """,
                            OffsetDateTime.class,
                            TENANT_A,
                            COMMITMENT_ID);

            assertThat(persisted)
                    .isEqualTo(CREATED_AT);
        }

        // ---------------------------------------------------------------------
        // Module persistence boundary
        // ---------------------------------------------------------------------

        @Test
        void inventorySchemaDoesNotCreateCrossModuleForeignKeys() {

            var foreignKeyCount =
                    jdbcTemplate.queryForObject("""
                            SELECT COUNT(*)
                            FROM pg_constraint constraint_metadata
                            JOIN pg_namespace namespace_metadata
                              ON namespace_metadata.oid =
                                 constraint_metadata.connamespace
                            WHERE namespace_metadata.nspname = 'inventory'
                              AND constraint_metadata.contype = 'f'
                            """,
                            Integer.class);

            assertThat(foreignKeyCount)
                    .isZero();
        }
    }

    private static boolean inventoryStructureExists() {

        var count =
                jdbcTemplate.queryForObject("""
                        SELECT COUNT(*)
                        FROM information_schema.tables
                        WHERE table_schema = 'inventory'
                          AND table_name IN (
                              'tenant_policies',
                              'inventory_positions',
                              'inventory_commitments'
                          )
                        """,
                        Integer.class);

        return count != null
                && count == 3;
    }

    private static void insertPolicy(
            UUID tenantId,
            String policy) {

        jdbcTemplate.update("""
                INSERT INTO inventory.tenant_policies (
                    tenant_id,
                    policy
                )
                VALUES (?, ?)
                """,
                tenantId,
                policy);
    }

    private static void insertPosition(
            UUID tenantId,
            UUID variantId,
            long onHand,
            long committed,
            long backordered,
            long safetyStock) {

        jdbcTemplate.update("""
                INSERT INTO inventory.inventory_positions (
                    tenant_id,
                    variant_id,
                    on_hand,
                    committed,
                    backordered,
                    safety_stock
                )
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                tenantId,
                variantId,
                onHand,
                committed,
                backordered,
                safetyStock);
    }

    private static void insertCommitment(
            UUID tenantId,
            UUID commitmentId,
            UUID orderId,
            UUID variantId,
            long requestedQuantity,
            long allocatedQuantity,
            long backorderedQuantity,
            OffsetDateTime createdAt) {

        jdbcTemplate.update("""
                INSERT INTO inventory.inventory_commitments (
                    tenant_id,
                    commitment_id,
                    order_id,
                    variant_id,
                    requested_quantity,
                    allocated_quantity,
                    backordered_quantity,
                    created_at
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                tenantId,
                commitmentId,
                orderId,
                variantId,
                requestedQuantity,
                allocatedQuantity,
                backorderedQuantity,
                createdAt);
    }

    private static void assertPostgresFailure(
            Throwable exception,
            String expectedSqlState,
            String expectedConstraint) {

        assertThat(exception)
                .isInstanceOf(
                        DataIntegrityViolationException.class);

        var postgresException =
                findCause(
                        exception,
                        PSQLException.class);

        assertThat((Object) postgresException).isNotNull();

        assertThat(postgresException.getSQLState())
                .isEqualTo(expectedSqlState);

        if (expectedConstraint != null) {
            assertThat(
                    postgresException
                            .getServerErrorMessage()
                            .getConstraint())
                    .isEqualTo(expectedConstraint);
        }
    }

    private static <T extends Throwable> T findCause(
            Throwable throwable,
            Class<T> expectedType) {

        var current =
                throwable;

        while (current != null) {

            if (expectedType.isInstance(current)) {
                return expectedType.cast(current);
            }

            current =
                    current.getCause();
        }

        return null;
    }
}
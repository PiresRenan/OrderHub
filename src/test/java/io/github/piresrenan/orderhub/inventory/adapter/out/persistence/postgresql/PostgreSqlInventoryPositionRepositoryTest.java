package io.github.piresrenan.orderhub.inventory.adapter.out.persistence.postgresql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import io.github.piresrenan.orderhub.inventory.application.port.out.InventoryPersistenceException;
import io.github.piresrenan.orderhub.inventory.application.port.out.InventoryPositionRepository;
import io.github.piresrenan.orderhub.inventory.domain.model.InsufficientInventoryException;
import io.github.piresrenan.orderhub.inventory.domain.model.InventoryAllocation;
import io.github.piresrenan.orderhub.inventory.domain.model.InventoryPolicy;

/**
 * Proves InventoryPosition persistence and atomic stock commitment against a
 * real PostgreSQL instance.
 *
 * <p>
 * The concurrency tests intentionally share PostgreSQL while executing through
 * independent JDBC connections. Correctness must come from database row
 * coordination and conditional mutation, never from a process-local lock.
 * </p>
 */
@Testcontainers
class PostgreSqlInventoryPositionRepositoryTest {

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

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(POSTGRES_IMAGE)
                    .withDatabaseName("orderhub_test")
                    .withUsername("orderhub_test")
                    .withPassword("synthetic-test-password");

    private static JdbcTemplate jdbcTemplate;

    private static InventoryPositionRepository repository;

    @BeforeAll
    static void migrateSchemaAndCreateRepository() {

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

        repository =
                new PostgreSqlInventoryPositionRepository(
                        jdbcTemplate);
    }

    @BeforeEach
    void cleanInventoryPositions() {

        jdbcTemplate.update("""
                TRUNCATE TABLE
                    inventory.inventory_positions
                """);
    }

    @Test
    void findsAndRehydratesInventoryPosition() {

        insertPosition(
                TENANT_A,
                VARIANT_ID,
                10,
                3,
                4,
                2);

        var result =
                repository.findById(
                        TENANT_A,
                        VARIANT_ID);

        assertThat(result)
                .isPresent();

        var position =
                result.orElseThrow();

        assertThat(position.tenantId())
                .isEqualTo(TENANT_A);

        assertThat(position.variantId())
                .isEqualTo(VARIANT_ID);

        assertThat(position.onHand())
                .isEqualTo(10);

        assertThat(position.committed())
                .isEqualTo(3);

        assertThat(position.backordered())
                .isEqualTo(4);

        assertThat(position.safetyStock())
                .isEqualTo(2);

        assertThat(position.availableToPromise())
                .isEqualTo(5);
    }

    @Test
    void returnsEmptyWhenPositionDoesNotExist() {

        assertThat(
                repository.findById(
                        TENANT_A,
                        VARIANT_ID))
                .isEmpty();
    }

    @Test
    void isolatesSameVariantIdentityAcrossTenants() {

        insertPosition(
                TENANT_A,
                VARIANT_ID,
                10,
                1,
                2,
                3);

        insertPosition(
                TENANT_B,
                VARIANT_ID,
                20,
                4,
                5,
                6);

        var tenantA =
                repository.findById(
                                TENANT_A,
                                VARIANT_ID)
                        .orElseThrow();

        var tenantB =
                repository.findById(
                                TENANT_B,
                                VARIANT_ID)
                        .orElseThrow();

        assertThat(tenantA.onHand())
                .isEqualTo(10);

        assertThat(tenantA.committed())
                .isEqualTo(1);

        assertThat(tenantB.onHand())
                .isEqualTo(20);

        assertThat(tenantB.committed())
                .isEqualTo(4);
    }

    @Test
    void denyPolicyAtomicallyAllocatesEntireRequest() {

        insertPosition(
                TENANT_A,
                VARIANT_ID,
                10,
                2,
                1,
                1);

        var allocation =
                repository.commit(
                        TENANT_A,
                        VARIANT_ID,
                        4,
                        InventoryPolicy.DENY);

        assertThat(allocation.requestedQuantity())
                .isEqualTo(4);

        assertThat(allocation.allocatedQuantity())
                .isEqualTo(4);

        assertThat(allocation.backorderedQuantity())
                .isZero();

        assertThat(allocation.position().committed())
                .isEqualTo(6);

        assertThat(allocation.position().backordered())
                .isEqualTo(1);

        assertThat(allocation.position().availableToPromise())
                .isEqualTo(3);

        var persisted =
                repository.findById(
                                TENANT_A,
                                VARIANT_ID)
                        .orElseThrow();

        assertThat(persisted.committed())
                .isEqualTo(6);

        assertThat(persisted.backordered())
                .isEqualTo(1);

        assertThat(persisted.availableToPromise())
                .isEqualTo(3);
    }

    @Test
    void denyPolicyAllowsExactAvailableToPromiseBoundary() {

        insertPosition(
                TENANT_A,
                VARIANT_ID,
                10,
                3,
                0,
                2);

        var allocation =
                repository.commit(
                        TENANT_A,
                        VARIANT_ID,
                        5,
                        InventoryPolicy.DENY);

        assertThat(allocation.allocatedQuantity())
                .isEqualTo(5);

        assertThat(allocation.backorderedQuantity())
                .isZero();

        assertThat(allocation.position().committed())
                .isEqualTo(8);

        assertThat(allocation.position().availableToPromise())
                .isZero();
    }

    @Test
    void denyPolicyRejectsInsufficientDemandWithoutMutation() {

        insertPosition(
                TENANT_A,
                VARIANT_ID,
                10,
                3,
                2,
                2);

        assertThatThrownBy(() ->
                repository.commit(
                        TENANT_A,
                        VARIANT_ID,
                        6,
                        InventoryPolicy.DENY))
                .isInstanceOf(
                        InsufficientInventoryException.class)
                .hasMessage(
                        "Insufficient inventory to commit requested quantity.");

        var persisted =
                repository.findById(
                                TENANT_A,
                                VARIANT_ID)
                        .orElseThrow();

        assertThat(persisted.onHand())
                .isEqualTo(10);

        assertThat(persisted.committed())
                .isEqualTo(3);

        assertThat(persisted.backordered())
                .isEqualTo(2);

        assertThat(persisted.safetyStock())
                .isEqualTo(2);

        assertThat(persisted.availableToPromise())
                .isEqualTo(5);
    }

    @Test
    void allowBackorderPolicySplitsDemandAtomically() {

        insertPosition(
                TENANT_A,
                VARIANT_ID,
                10,
                3,
                0,
                2);

        var allocation =
                repository.commit(
                        TENANT_A,
                        VARIANT_ID,
                        8,
                        InventoryPolicy.ALLOW_BACKORDER);

        assertThat(allocation.requestedQuantity())
                .isEqualTo(8);

        assertThat(allocation.allocatedQuantity())
                .isEqualTo(5);

        assertThat(allocation.backorderedQuantity())
                .isEqualTo(3);

        assertThat(allocation.position().committed())
                .isEqualTo(8);

        assertThat(allocation.position().backordered())
                .isEqualTo(3);

        assertThat(allocation.position().availableToPromise())
                .isZero();
    }

    @Test
    void allowBackorderPolicyBackordersEntireDemandWhenAtpIsNegative() {

        insertPosition(
                TENANT_A,
                VARIANT_ID,
                10,
                8,
                4,
                5);

        var allocation =
                repository.commit(
                        TENANT_A,
                        VARIANT_ID,
                        3,
                        InventoryPolicy.ALLOW_BACKORDER);

        assertThat(allocation.allocatedQuantity())
                .isZero();

        assertThat(allocation.backorderedQuantity())
                .isEqualTo(3);

        assertThat(allocation.position().committed())
                .isEqualTo(8);

        assertThat(allocation.position().backordered())
                .isEqualTo(7);

        assertThat(allocation.position().availableToPromise())
                .isEqualTo(-3);
    }

    @Test
    void allowBackorderPolicyPreservesPreviousBackorderedDemand() {

        insertPosition(
                TENANT_A,
                VARIANT_ID,
                10,
                10,
                10,
                0);

        var allocation =
                repository.commit(
                        TENANT_A,
                        VARIANT_ID,
                        4,
                        InventoryPolicy.ALLOW_BACKORDER);

        assertThat(allocation.allocatedQuantity())
                .isZero();

        assertThat(allocation.backorderedQuantity())
                .isEqualTo(4);

        assertThat(allocation.position().backordered())
                .isEqualTo(14);
    }

    @Test
    void rejectsNonPositiveRequestedQuantityBeforeDatabaseMutation() {

        insertPosition(
                TENANT_A,
                VARIANT_ID,
                10,
                0,
                0,
                0);

        assertThatThrownBy(() ->
                repository.commit(
                        TENANT_A,
                        VARIANT_ID,
                        0,
                        InventoryPolicy.DENY))
                .isInstanceOf(
                        IllegalArgumentException.class)
                .hasMessage(
                        "Inventory requested quantity must be greater than zero");

        assertThatThrownBy(() ->
                repository.commit(
                        TENANT_A,
                        VARIANT_ID,
                        -1,
                        InventoryPolicy.DENY))
                .isInstanceOf(
                        IllegalArgumentException.class)
                .hasMessage(
                        "Inventory requested quantity must be greater than zero");

        var persisted =
                repository.findById(
                                TENANT_A,
                                VARIANT_ID)
                        .orElseThrow();

        assertThat(persisted.committed())
                .isZero();

        assertThat(persisted.backordered())
                .isZero();
    }

    @Test
    void requiresInventoryPolicyBeforeDatabaseMutation() {

        insertPosition(
                TENANT_A,
                VARIANT_ID,
                10,
                0,
                0,
                0);

        assertThatThrownBy(() ->
                repository.commit(
                        TENANT_A,
                        VARIANT_ID,
                        1,
                        null))
                .isInstanceOf(
                        IllegalArgumentException.class)
                .hasMessage(
                        "Inventory policy is required");

        var persisted =
                repository.findById(
                                TENANT_A,
                                VARIANT_ID)
                        .orElseThrow();

        assertThat(persisted.committed())
                .isZero();

        assertThat(persisted.backordered())
                .isZero();
    }

    @Test
    void translatesBackorderedAccumulatorOverflowWithoutPartialMutation() {

        insertPosition(
                TENANT_A,
                VARIANT_ID,
                10,
                10,
                Long.MAX_VALUE,
                0);

        assertThatThrownBy(() ->
                repository.commit(
                        TENANT_A,
                        VARIANT_ID,
                        1,
                        InventoryPolicy.ALLOW_BACKORDER))
                .isInstanceOf(
                        InventoryPersistenceException.class)
                .hasMessage(
                        "Inventory persistence operation failed.")
                .hasCauseInstanceOf(
                        org.springframework.dao.DataAccessException.class);

        var persisted =
                repository.findById(
                                TENANT_A,
                                VARIANT_ID)
                        .orElseThrow();

        assertThat(persisted.committed())
                .isEqualTo(10);

        assertThat(persisted.backordered())
                .isEqualTo(Long.MAX_VALUE);
    }

    @Test
    void concurrentDenyRequestsCannotCommitLastUnitTwice()
            throws Exception {

        insertPosition(
                TENANT_A,
                VARIANT_ID,
                1,
                0,
                0,
                0);

        var attempts =
                runConcurrently(
                        () -> repository.commit(
                                TENANT_A,
                                VARIANT_ID,
                                1,
                                InventoryPolicy.DENY),
                        () -> repository.commit(
                                TENANT_A,
                                VARIANT_ID,
                                1,
                                InventoryPolicy.DENY));

        assertThat(
                attempts.stream()
                        .filter(CommitAttempt::succeeded)
                        .toList())
                .hasSize(1);

        assertThat(
                attempts.stream()
                        .filter(attempt ->
                                attempt.failure()
                                        instanceof InsufficientInventoryException)
                        .toList())
                .hasSize(1);

        var successfulAllocation =
                attempts.stream()
                        .filter(CommitAttempt::succeeded)
                        .map(CommitAttempt::allocation)
                        .findFirst()
                        .orElseThrow();

        assertThat(successfulAllocation.allocatedQuantity())
                .isEqualTo(1);

        assertThat(successfulAllocation.backorderedQuantity())
                .isZero();

        var persisted =
                repository.findById(
                                TENANT_A,
                                VARIANT_ID)
                        .orElseThrow();

        assertThat(persisted.committed())
                .isEqualTo(1);

        assertThat(persisted.backordered())
                .isZero();

        assertThat(persisted.availableToPromise())
                .isZero();
    }

    @Test
    void concurrentBackorderRequestsProduceConsistentTotals()
            throws Exception {

        insertPosition(
                TENANT_A,
                VARIANT_ID,
                1,
                0,
                0,
                0);

        var attempts =
                runConcurrently(
                        () -> repository.commit(
                                TENANT_A,
                                VARIANT_ID,
                                1,
                                InventoryPolicy.ALLOW_BACKORDER),
                        () -> repository.commit(
                                TENANT_A,
                                VARIANT_ID,
                                1,
                                InventoryPolicy.ALLOW_BACKORDER));

        assertThat(attempts)
                .allSatisfy(attempt ->
                        assertThat(attempt.failure())
                                .isNull());

        var totalAllocated =
                attempts.stream()
                        .map(CommitAttempt::allocation)
                        .mapToLong(
                                InventoryAllocation::allocatedQuantity)
                        .sum();

        var totalBackordered =
                attempts.stream()
                        .map(CommitAttempt::allocation)
                        .mapToLong(
                                InventoryAllocation::backorderedQuantity)
                        .sum();

        assertThat(totalAllocated)
                .isEqualTo(1);

        assertThat(totalBackordered)
                .isEqualTo(1);

        var persisted =
                repository.findById(
                                TENANT_A,
                                VARIANT_ID)
                        .orElseThrow();

        assertThat(persisted.committed())
                .isEqualTo(1);

        assertThat(persisted.backordered())
                .isEqualTo(1);

        assertThat(persisted.availableToPromise())
                .isZero();
    }

    @Test
    void concurrentMutationsOfSameVariantIdentityRemainTenantIsolated()
            throws Exception {

        insertPosition(
                TENANT_A,
                VARIANT_ID,
                1,
                0,
                0,
                0);

        insertPosition(
                TENANT_B,
                VARIANT_ID,
                1,
                0,
                0,
                0);

        var attempts =
                runConcurrently(
                        () -> repository.commit(
                                TENANT_A,
                                VARIANT_ID,
                                1,
                                InventoryPolicy.DENY),
                        () -> repository.commit(
                                TENANT_B,
                                VARIANT_ID,
                                1,
                                InventoryPolicy.DENY));

        assertThat(attempts)
                .allSatisfy(attempt -> {

                    assertThat(attempt.failure())
                            .isNull();

                    assertThat(
                            attempt.allocation()
                                    .allocatedQuantity())
                            .isEqualTo(1);
                });

        var tenantA =
                repository.findById(
                                TENANT_A,
                                VARIANT_ID)
                        .orElseThrow();

        var tenantB =
                repository.findById(
                                TENANT_B,
                                VARIANT_ID)
                        .orElseThrow();

        assertThat(tenantA.committed())
                .isEqualTo(1);

        assertThat(tenantB.committed())
                .isEqualTo(1);

        assertThat(tenantA.availableToPromise())
                .isZero();

        assertThat(tenantB.availableToPromise())
                .isZero();
    }

    private static List<CommitAttempt> runConcurrently(
            Callable<InventoryAllocation> firstOperation,
            Callable<InventoryAllocation> secondOperation)
            throws Exception {

        var executor =
                Executors.newFixedThreadPool(2);

        var ready =
                new CountDownLatch(2);

        var start =
                new CountDownLatch(1);

        try {
            Future<CommitAttempt> first =
                    executor.submit(
                            concurrentAttempt(
                                    firstOperation,
                                    ready,
                                    start));

            Future<CommitAttempt> second =
                    executor.submit(
                            concurrentAttempt(
                                    secondOperation,
                                    ready,
                                    start));

            assertThat(
                    ready.await(
                            5,
                            TimeUnit.SECONDS))
                    .as("Both concurrent workers became ready")
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

    private static Callable<CommitAttempt> concurrentAttempt(
            Callable<InventoryAllocation> operation,
            CountDownLatch ready,
            CountDownLatch start) {

        return () -> {

            ready.countDown();

            if (!start.await(
                    5,
                    TimeUnit.SECONDS)) {

                throw new IllegalStateException(
                        "Concurrent Inventory test start gate timed out.");
            }

            try {
                return CommitAttempt.success(
                        operation.call());
            } catch (Throwable failure) {
                return CommitAttempt.failure(
                        failure);
            }
        };
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

    private record CommitAttempt(
            InventoryAllocation allocation,
            Throwable failure) {

        static CommitAttempt success(
                InventoryAllocation allocation) {

            return new CommitAttempt(
                    allocation,
                    null);
        }

        static CommitAttempt failure(
                Throwable failure) {

            return new CommitAttempt(
                    null,
                    failure);
        }

        boolean succeeded() {
            return failure == null;
        }
    }
}
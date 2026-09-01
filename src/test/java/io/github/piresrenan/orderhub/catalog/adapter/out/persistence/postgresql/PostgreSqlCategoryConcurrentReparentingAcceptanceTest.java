package io.github.piresrenan.orderhub.catalog.adapter.out.persistence.postgresql;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import io.github.piresrenan.orderhub.catalog.adapter.out.transaction.postgresql.PostgreSqlCategoryHierarchyMutationExecutor;
import io.github.piresrenan.orderhub.catalog.application.port.in.CategoryHierarchyViolationException;
import io.github.piresrenan.orderhub.catalog.application.port.out.CatalogPersistenceException;
import io.github.piresrenan.orderhub.catalog.application.port.out.CategoryRepository;
import io.github.piresrenan.orderhub.catalog.application.service.SaveCategoryService;
import io.github.piresrenan.orderhub.catalog.domain.model.Category;

/**
 * Real PostgreSQL acceptance proofs for OH-011 Category hierarchy
 * concurrency guarantees.
 */
@Testcontainers
class PostgreSqlCategoryConcurrentReparentingAcceptanceTest {

    private static final DockerImageName POSTGRES_IMAGE =
            DockerImageName.parse(
                    "postgres:18.6-trixie@sha256:"
                            + "4ef4dbc939d61acea57712655ddb4b4ab27419c913f94cca0cd57cb3ea3c2280")
                    .asCompatibleSubstituteFor(
                            "postgres");

    private static final UUID TENANT_ID =
            UUID.fromString(
                    "11111111-1111-1111-1111-111111111111");

    private static final UUID OTHER_TENANT_ID =
            UUID.fromString(
                    "22222222-2222-2222-2222-222222222222");

    private static final UUID CATEGORY_A_ID =
            UUID.fromString(
                    "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa1");

    private static final UUID CATEGORY_B_ID =
            UUID.fromString(
                    "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb2");

    private static final UUID FIRST_USE_A_ID =
            UUID.fromString(
                    "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaa3");

    private static final UUID FIRST_USE_B_ID =
            UUID.fromString(
                    "bbbbbbbb-bbbb-bbbb-bbbb-bbbbbbbbbbb4");

    private static final UUID OTHER_TENANT_CATEGORY_ID =
            UUID.fromString(
                    "cccccccc-cccc-cccc-cccc-ccccccccccc5");

    private static final UUID TIMEOUT_CATEGORY_ID =
            UUID.fromString(
                    "dddddddd-dddd-dddd-dddd-ddddddddddd6");

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(
                    POSTGRES_IMAGE)
                    .withDatabaseName(
                            "orderhub_test")
                    .withUsername(
                            "orderhub_test")
                    .withPassword(
                            "synthetic-test-password");

    private static DriverManagerDataSource dataSource;
    private static JdbcTemplate jdbcTemplate;

    private CategoryRepository repository;
    private SaveCategoryService service;

    @BeforeAll
    static void migrateSchema() {

        dataSource =
                new DriverManagerDataSource(
                        POSTGRES.getJdbcUrl(),
                        POSTGRES.getUsername(),
                        POSTGRES.getPassword());

        Flyway.configure()
                .dataSource(
                        dataSource)
                .locations(
                        "classpath:db/migration")
                .load()
                .migrate();

        jdbcTemplate =
                new JdbcTemplate(
                        dataSource);
    }

    @BeforeEach
    void resetCatalog() {

        jdbcTemplate.update("""
                TRUNCATE TABLE
                    catalog.category_hierarchy_guards,
                    catalog.media,
                    catalog.variant_base_prices,
                    catalog.product_variant_attributes,
                    catalog.product_categories,
                    catalog.product_variants,
                    catalog.categories,
                    catalog.products
                """);

        repository =
                new PostgreSqlCategoryRepository(
                        jdbcTemplate);

        service =
                serviceWithTimeout(
                        5);
    }

    @Test
    void concurrentInverseReparentingAcceptsExactlyOneAndRejectsTheOtherAsHierarchyViolation()
            throws Exception {

        service.save(
                category(
                        TENANT_ID,
                        CATEGORY_A_ID,
                        null,
                        "category-a"));

        service.save(
                category(
                        TENANT_ID,
                        CATEGORY_B_ID,
                        null,
                        "category-b"));

        var attempts =
                runConcurrently(
                        () ->
                                service.save(
                                        category(
                                                TENANT_ID,
                                                CATEGORY_A_ID,
                                                CATEGORY_B_ID,
                                                "category-a")),
                        () ->
                                service.save(
                                        category(
                                                TENANT_ID,
                                                CATEGORY_B_ID,
                                                CATEGORY_A_ID,
                                                "category-b")));

        var successfulMutations =
                attempts.stream()
                        .filter(attempt ->
                                attempt.failure() == null)
                        .count();

        var failures =
                attempts.stream()
                        .map(Attempt::failure)
                        .filter(failure ->
                                failure != null)
                        .toList();

        var persistedAParent =
                parentOf(
                        TENANT_ID,
                        CATEGORY_A_ID);

        var persistedBParent =
                parentOf(
                        TENANT_ID,
                        CATEGORY_B_ID);

        var twoNodeCycle =
                CATEGORY_B_ID.equals(
                        persistedAParent)
                        && CATEGORY_A_ID.equals(
                                persistedBParent);

        assertThat(successfulMutations)
                .as(
                        "Exactly one inverse hierarchy mutation is accepted")
                .isEqualTo(1);

        assertThat(failures)
                .as(
                        "The competing mutation is rejected exactly once")
                .singleElement()
                .isInstanceOf(
                        CategoryHierarchyViolationException.class);

        assertThat(twoNodeCycle)
                .as(
                        "A durable A <-> B Category cycle is forbidden")
                .isFalse();

        assertThat(categoryCount(
                TENANT_ID))
                .as(
                        "The race changes hierarchy only and loses no rows")
                .isEqualTo(2);

        assertThat(guardCount(
                TENANT_ID))
                .as(
                        "Exactly one hierarchy guard exists for the Tenant")
                .isEqualTo(1);
    }

    @Test
    void concurrentFirstUseOfTenantGuardConvergesOnOneDurableGuardRow()
            throws Exception {

        assertThat(guardCount(
                TENANT_ID))
                .as(
                        "The guard must not be pre-seeded")
                .isZero();

        var attempts =
                runConcurrently(
                        () ->
                                service.save(
                                        category(
                                                TENANT_ID,
                                                FIRST_USE_A_ID,
                                                null,
                                                "first-use-a")),
                        () ->
                                service.save(
                                        category(
                                                TENANT_ID,
                                                FIRST_USE_B_ID,
                                                null,
                                                "first-use-b")));

        assertThat(attempts)
                .allSatisfy(attempt ->
                        assertThat(attempt.failure())
                                .isNull());

        assertThat(categoryCount(
                TENANT_ID))
                .isEqualTo(2);

        assertThat(guardCount(
                TENANT_ID))
                .as(
                        "Concurrent first use converges on one Tenant guard")
                .isEqualTo(1);
    }

    @Test
    void heldGuardForOneTenantDoesNotBlockHierarchyMutationForAnotherTenant()
            throws Exception {

        ensureGuard(
                TENANT_ID);

        var executor =
                Executors.newSingleThreadExecutor();

        try (
            var lockConnection =
                    dataSource.getConnection()
        ) {

            lockConnection.setAutoCommit(
                    false);

            lockGuard(
                    lockConnection,
                    TENANT_ID);

            var future =
                    executor.submit(() ->
                            attempt(() ->
                                    service.save(
                                            category(
                                                    OTHER_TENANT_ID,
                                                    OTHER_TENANT_CATEGORY_ID,
                                                    null,
                                                    "other-tenant-root"))));

            Attempt outcome;

            try {
                outcome =
                        future.get(
                                3,
                                TimeUnit.SECONDS);

            } finally {

                lockConnection.rollback();

                executor.shutdownNow();

                executor.awaitTermination(
                        5,
                        TimeUnit.SECONDS);
            }

            assertThat(outcome.failure())
                    .as(
                            "Tenant B must complete while Tenant A guard remains held")
                    .isNull();
        }

        assertThat(categoryCount(
                OTHER_TENANT_ID))
                .isEqualTo(1);

        assertThat(guardCount(
                OTHER_TENANT_ID))
                .isEqualTo(1);

        assertThat(guardCount(
                TENANT_ID))
                .isEqualTo(1);
    }

    @Test
    void configuredTransactionTimeoutTerminatesSameTenantGuardContentionWithoutMutation()
            throws Exception {

        ensureGuard(
                TENANT_ID);

        var oneSecondService =
                serviceWithTimeout(
                        1);

        var executor =
                Executors.newSingleThreadExecutor();

        Attempt outcome;
        long elapsedMillis;

        try (
            var lockConnection =
                    dataSource.getConnection()
        ) {

            lockConnection.setAutoCommit(
                    false);

            lockGuard(
                    lockConnection,
                    TENANT_ID);

            var startedAt =
                    System.nanoTime();

            var future =
                    executor.submit(() ->
                            attempt(() ->
                                    oneSecondService.save(
                                            category(
                                                    TENANT_ID,
                                                    TIMEOUT_CATEGORY_ID,
                                                    null,
                                                    "timeout-root"))));

            try {
                outcome =
                        future.get(
                                5,
                                TimeUnit.SECONDS);

                elapsedMillis =
                        TimeUnit.NANOSECONDS.toMillis(
                                System.nanoTime()
                                        - startedAt);

            } finally {

                lockConnection.rollback();

                executor.shutdownNow();

                executor.awaitTermination(
                        5,
                        TimeUnit.SECONDS);
            }
        }

        assertThat(outcome.failure())
                .as(
                        "Guard timeout remains a technical failure")
                .isInstanceOf(
                        CatalogPersistenceException.class);

        assertThat(outcome.failure())
                .isNotInstanceOf(
                        CategoryHierarchyViolationException.class);

        assertThat(elapsedMillis)
                .as(
                        "Configured transaction timeout bounds same-Tenant lock wait")
                .isLessThan(
                        5_000L);

        assertThat(categoryCount(
                TENANT_ID))
                .as(
                        "Timed-out hierarchy mutation leaves no Category effect")
                .isZero();

        assertThat(guardCount(
                TENANT_ID))
                .as(
                        "The pre-existing guard remains intact")
                .isEqualTo(1);
    }

    private SaveCategoryService serviceWithTimeout(
            int timeoutSeconds) {

        var transactionManager =
                new DataSourceTransactionManager(
                        dataSource);

        var transactionTemplate =
                new TransactionTemplate(
                        transactionManager);

        transactionTemplate.setTimeout(
                timeoutSeconds);

        var mutationExecutor =
                new PostgreSqlCategoryHierarchyMutationExecutor(
                        jdbcTemplate,
                        transactionTemplate);

        return new SaveCategoryService(
                repository,
                mutationExecutor);
    }

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
            var first =
                    executor.submit(() -> {

                        ready.countDown();

                        start.await();

                        return attempt(
                                firstOperation);
                    });

            var second =
                    executor.submit(() -> {

                        ready.countDown();

                        start.await();

                        return attempt(
                                secondOperation);
                    });

            assertThat(
                    ready.await(
                            5,
                            TimeUnit.SECONDS))
                    .as(
                            "Both hierarchy workers became ready")
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

    private void ensureGuard(
            UUID tenantId) {

        jdbcTemplate.update("""
                INSERT INTO catalog.category_hierarchy_guards (
                    tenant_id
                )
                VALUES (?)
                ON CONFLICT (tenant_id)
                DO NOTHING
                """,
                tenantId);
    }

    private static void lockGuard(
            Connection connection,
            UUID tenantId)
            throws Exception {

        try (
            var statement =
                    connection.prepareStatement("""
                            SELECT tenant_id
                            FROM catalog.category_hierarchy_guards
                            WHERE tenant_id = ?
                            FOR UPDATE
                            """)
        ) {

            statement.setObject(
                    1,
                    tenantId);

            try (
                var resultSet =
                        statement.executeQuery()
            ) {

                assertThat(resultSet.next())
                        .as(
                                "Hierarchy guard row exists")
                        .isTrue();

                assertThat(
                        resultSet.getObject(
                                "tenant_id",
                                UUID.class))
                        .isEqualTo(
                                tenantId);

                assertThat(resultSet.next())
                        .isFalse();
            }
        }
    }

    private UUID parentOf(
            UUID tenantId,
            UUID categoryId) {

        return jdbcTemplate.queryForObject("""
                SELECT parent_category_id
                FROM catalog.categories
                WHERE tenant_id = ?
                  AND id = ?
                """,
                UUID.class,
                tenantId,
                categoryId);
    }

    private long categoryCount(
            UUID tenantId) {

        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM catalog.categories
                WHERE tenant_id = ?
                """,
                Long.class,
                tenantId);
    }

    private long guardCount(
            UUID tenantId) {

        return jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM catalog.category_hierarchy_guards
                WHERE tenant_id = ?
                """,
                Long.class,
                tenantId);
    }

    private static Category category(
            UUID tenantId,
            UUID id,
            UUID parentCategoryId,
            String slug) {

        return Category.create(
                id,
                tenantId,
                parentCategoryId,
                "Category " + slug,
                slug,
                null);
    }

    private static Attempt attempt(
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

    private record Attempt(
            Throwable failure) {
    }
}

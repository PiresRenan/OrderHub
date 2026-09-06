package io.github.piresrenan.orderhub.organizations.adapter.out.persistence.postgresql;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.CyclicBarrier;
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

import io.github.piresrenan.orderhub.organizations.application.port.out.OrganizationLifecycleMutationResult;
import io.github.piresrenan.orderhub.organizations.application.port.out.OrganizationTenantPlacementResult;
import io.github.piresrenan.orderhub.organizations.domain.model.Organization;
import io.github.piresrenan.orderhub.organizations.domain.model.OrganizationStatus;

@Testcontainers
class OrganizationLifecyclePlacementConcurrencyTest {

    private static final DockerImageName POSTGRES_IMAGE =
            DockerImageName.parse(
                    "postgres:18.6-trixie@sha256:"
                            + "4ef4dbc939d61acea57712655ddb4b4ab27419c913f94cca0cd57cb3ea3c2280")
                    .asCompatibleSubstituteFor("postgres");

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(POSTGRES_IMAGE)
                    .withDatabaseName("orderhub_test")
                    .withUsername("orderhub_test")
                    .withPassword("synthetic-test-password");

    private static JdbcTemplate jdbcTemplate;
    private static TransactionTemplate transactionTemplate;
    private static PostgreSqlOrganizationRepository organizationRepository;
    private static PostgreSqlOrganizationLifecycleRepository lifecycleRepository;
    private static PostgreSqlOrganizationTenantPlacementRepository placementRepository;

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
                new JdbcTemplate(
                        dataSource);

        var transactionManager =
                new DataSourceTransactionManager(
                        dataSource);

        transactionTemplate =
                new TransactionTemplate(
                        transactionManager);

        organizationRepository =
                new PostgreSqlOrganizationRepository(
                        jdbcTemplate);

        lifecycleRepository =
                new PostgreSqlOrganizationLifecycleRepository(
                        jdbcTemplate,
                        transactionManager);

        placementRepository =
                new PostgreSqlOrganizationTenantPlacementRepository(
                        jdbcTemplate,
                        transactionManager);
    }

    @BeforeEach
    void clearState() {

        jdbcTemplate.update(
                """
                TRUNCATE TABLE
                    organizations.tenant_placements,
                    organizations.organizations
                """);
    }

    @Test
    void placementShareLockBlocksLifecycleWriterUntilPlacementTransactionEnds()
            throws Exception {

        var organization =
                seedOrganization(
                        "Shared Lock Organization");

        var sharedLockAcquired =
                new CountDownLatch(
                        1);

        var releaseSharedLock =
                new CountDownLatch(
                        1);

        var lifecycleWriterStarted =
                new CountDownLatch(
                        1);

        try (var executor =
                Executors.newFixedThreadPool(
                        2)) {

            var sharedLockFuture =
                    executor.submit(() -> {

                        transactionTemplate.executeWithoutResult(
                                transactionStatus -> {

                                    jdbcTemplate.queryForObject(
                                            """
                                            SELECT status
                                            FROM organizations.organizations
                                            WHERE id = ?
                                            FOR SHARE
                                            """,
                                            String.class,
                                            organization.id());

                                    sharedLockAcquired.countDown();

                                    awaitLatch(
                                            releaseSharedLock,
                                            "shared placement lock release");
                                });

                        return null;
                    });

            awaitLatch(
                    sharedLockAcquired,
                    "shared placement lock acquisition");

            var lifecycleFuture =
                    executor.submit(() -> {

                        lifecycleWriterStarted.countDown();

                        return lifecycleRepository.setStatus(
                                organization.id(),
                                OrganizationStatus.SUSPENDED);
                    });

            awaitLatch(
                    lifecycleWriterStarted,
                    "lifecycle writer start");

            try {
                Thread.sleep(
                        250);

                assertThat(
                        lifecycleFuture.isDone())
                        .as(
                                "lifecycle writer must wait for the placement-style FOR SHARE lock")
                        .isFalse();

            } finally {
                releaseSharedLock.countDown();
            }

            assertThat(
                    lifecycleFuture.get(
                            10,
                            TimeUnit.SECONDS))
                    .isEqualTo(
                            OrganizationLifecycleMutationResult.UPDATED);

            sharedLockFuture.get(
                    10,
                    TimeUnit.SECONDS);
        }

        assertThat(
                organizationRepository.findById(
                        organization.id()))
                .get()
                .extracting(
                        persisted ->
                                persisted.status())
                .isEqualTo(
                        OrganizationStatus.SUSPENDED);
    }

    @Test
    void placementWaitsForLifecycleMutationAndObservesCommittedSuspension()
            throws Exception {

        var organization =
                seedOrganization(
                        "Lifecycle Lock Organization");

        var tenantId =
                UUID.randomUUID();

        var lifecycleMutationReady =
                new CountDownLatch(
                        1);

        var releaseLifecycleMutation =
                new CountDownLatch(
                        1);

        var placementStarted =
                new CountDownLatch(
                        1);

        try (var executor =
                Executors.newFixedThreadPool(
                        2)) {

            var lifecycleFuture =
                    executor.submit(() -> {

                        transactionTemplate.executeWithoutResult(
                                transactionStatus -> {

                                    jdbcTemplate.queryForObject(
                                            """
                                            SELECT status
                                            FROM organizations.organizations
                                            WHERE id = ?
                                            FOR NO KEY UPDATE
                                            """,
                                            String.class,
                                            organization.id());

                                    jdbcTemplate.update(
                                            """
                                            UPDATE organizations.organizations
                                            SET status = 'SUSPENDED'
                                            WHERE id = ?
                                            """,
                                            organization.id());

                                    lifecycleMutationReady.countDown();

                                    awaitLatch(
                                            releaseLifecycleMutation,
                                            "lifecycle mutation release");
                                });

                        return null;
                    });

            awaitLatch(
                    lifecycleMutationReady,
                    "lifecycle mutation readiness");

            var placementFuture =
                    executor.submit(() -> {

                        placementStarted.countDown();

                        return placementRepository.attach(
                                tenantId,
                                organization.id());
                    });

            awaitLatch(
                    placementStarted,
                    "placement start");

            try {
                Thread.sleep(
                        250);

                assertThat(
                        placementFuture.isDone())
                        .as(
                                "placement must wait while lifecycle holds FOR NO KEY UPDATE")
                        .isFalse();

            } finally {
                releaseLifecycleMutation.countDown();
            }

            lifecycleFuture.get(
                    10,
                    TimeUnit.SECONDS);

            assertThat(
                    placementFuture.get(
                            10,
                            TimeUnit.SECONDS))
                    .isEqualTo(
                            OrganizationTenantPlacementResult.DESTINATION_SUSPENDED);
        }

        assertThat(
                placementRepository.findByTenantId(
                        tenantId))
                .isEmpty();
    }

    @Test
    void concurrentRealSuspendAndAttachProduceOnlySerializableValidOutcome()
            throws Exception {

        for (var iteration = 0;
                iteration < 20;
                iteration++) {

            var organization =
                    seedOrganization(
                            "Concurrent Organization "
                                    + iteration);

            var tenantId =
                    UUID.randomUUID();

            var barrier =
                    new CyclicBarrier(
                            2);

            try (var executor =
                    Executors.newFixedThreadPool(
                            2)) {

                var lifecycleFuture =
                        executor.submit(() -> {

                            barrier.await(
                                    10,
                                    TimeUnit.SECONDS);

                            return lifecycleRepository.setStatus(
                                    organization.id(),
                                    OrganizationStatus.SUSPENDED);
                        });

                var placementFuture =
                        executor.submit(() -> {

                            barrier.await(
                                    10,
                                    TimeUnit.SECONDS);

                            return placementRepository.attach(
                                    tenantId,
                                    organization.id());
                        });

                var lifecycleResult =
                        lifecycleFuture.get(
                                15,
                                TimeUnit.SECONDS);

                var placementResult =
                        placementFuture.get(
                                15,
                                TimeUnit.SECONDS);

                assertThat(lifecycleResult)
                        .isEqualTo(
                                OrganizationLifecycleMutationResult.UPDATED);

                assertThat(placementResult)
                        .isIn(
                                OrganizationTenantPlacementResult.ATTACHED,
                                OrganizationTenantPlacementResult.DESTINATION_SUSPENDED);

                assertThat(
                        organizationRepository.findById(
                                organization.id()))
                        .get()
                        .extracting(
                                persisted ->
                                        persisted.status())
                        .isEqualTo(
                                OrganizationStatus.SUSPENDED);

                if (placementResult
                        == OrganizationTenantPlacementResult.ATTACHED) {

                    assertThat(
                            placementRepository.findByTenantId(
                                    tenantId))
                            .get()
                            .extracting(
                                    placement ->
                                            placement.organizationId())
                            .isEqualTo(
                                    organization.id());

                } else {

                    assertThat(
                            placementRepository.findByTenantId(
                                    tenantId))
                            .isEmpty();
                }
            }
        }
    }

    private Organization seedOrganization(
            String name) {

        var organization =
                Organization.create(
                        UUID.randomUUID(),
                        name);

        organizationRepository.save(
                organization);

        return organization;
    }

    private static void awaitLatch(
            CountDownLatch latch,
            String description) {

        try {
            if (!latch.await(
                    10,
                    TimeUnit.SECONDS)) {

                throw new AssertionError(
                        "Timed out waiting for "
                                + description);
            }

        } catch (InterruptedException exception) {

            Thread.currentThread()
                    .interrupt();

            throw new AssertionError(
                    "Interrupted while waiting for "
                            + description,
                    exception);
        }
    }
}

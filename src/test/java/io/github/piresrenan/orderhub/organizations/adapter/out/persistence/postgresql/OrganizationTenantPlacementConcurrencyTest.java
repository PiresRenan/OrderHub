package io.github.piresrenan.orderhub.organizations.adapter.out.persistence.postgresql;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
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
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import io.github.piresrenan.orderhub.organizations.application.port.out.OrganizationTenantPlacementResult;

@Testcontainers
class OrganizationTenantPlacementConcurrencyTest {

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
    private static PostgreSqlOrganizationTenantPlacementRepository repository;

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

        repository =
                new PostgreSqlOrganizationTenantPlacementRepository(
                        jdbcTemplate,
                        new DataSourceTransactionManager(
                                dataSource));
    }

    @BeforeEach
    void clearState() {

        jdbcTemplate.update(
                "TRUNCATE TABLE organizations.tenant_placements");

        jdbcTemplate.update(
                "TRUNCATE TABLE organizations.organizations CASCADE");
    }

    @Test
    void concurrentAttachToDifferentOrganizationsPreservesSingleParent() {

        var tenantId =
                UUID.randomUUID();

        var organizationA =
                seedOrganization(
                        "Organization A");

        var organizationB =
                seedOrganization(
                        "Organization B");

        var results =
                executeConcurrently(
                        () ->
                                repository.attach(
                                        tenantId,
                                        organizationA),
                        () ->
                                repository.attach(
                                        tenantId,
                                        organizationB));

        assertThat(results)
                .containsExactlyInAnyOrder(
                        OrganizationTenantPlacementResult.ATTACHED,
                        OrganizationTenantPlacementResult.CONFLICT);

        assertThat(
                repository.findByTenantId(
                        tenantId))
                .get()
                .extracting(
                        placement ->
                                placement.organizationId())
                .isIn(
                        organizationA,
                        organizationB);

        assertThat(
                countPlacements(
                        tenantId))
                .isEqualTo(
                        1);
    }

    @Test
    void concurrentAttachToSameOrganizationIsIdempotent() {

        var tenantId =
                UUID.randomUUID();

        var organizationId =
                seedOrganization(
                        "Organization");

        var results =
                executeConcurrently(
                        () ->
                                repository.attach(
                                        tenantId,
                                        organizationId),
                        () ->
                                repository.attach(
                                        tenantId,
                                        organizationId));

        assertThat(results)
                .containsExactlyInAnyOrder(
                        OrganizationTenantPlacementResult.ATTACHED,
                        OrganizationTenantPlacementResult.ALREADY_ATTACHED);

        assertThat(
                countPlacements(
                        tenantId))
                .isEqualTo(
                        1);
    }

    @Test
    void concurrentMovesFromSameExpectedSourceAllowOnlyOneWinner() {

        var tenantId =
                UUID.randomUUID();

        var source =
                seedOrganization(
                        "Source");

        var destinationB =
                seedOrganization(
                        "Destination B");

        var destinationC =
                seedOrganization(
                        "Destination C");

        repository.attach(
                tenantId,
                source);

        var results =
                executeConcurrently(
                        () ->
                                repository.move(
                                        tenantId,
                                        source,
                                        destinationB),
                        () ->
                                repository.move(
                                        tenantId,
                                        source,
                                        destinationC));

        assertThat(results)
                .containsExactlyInAnyOrder(
                        OrganizationTenantPlacementResult.MOVED,
                        OrganizationTenantPlacementResult.CONFLICT);

        assertThat(
                repository.findByTenantId(
                        tenantId))
                .get()
                .extracting(
                        placement ->
                                placement.organizationId())
                .isIn(
                        destinationB,
                        destinationC);

        assertThat(
                countPlacements(
                        tenantId))
                .isEqualTo(
                        1);
    }

    @Test
    void concurrentMoveAndExpectedDetachCannotDeleteACompletedMove() {

        var tenantId =
                UUID.randomUUID();

        var source =
                seedOrganization(
                        "Source");

        var destination =
                seedOrganization(
                        "Destination");

        repository.attach(
                tenantId,
                source);

        var results =
                executeConcurrently(
                        () ->
                                repository.move(
                                        tenantId,
                                        source,
                                        destination),
                        () ->
                                repository.detach(
                                        tenantId,
                                        source));

        assertThat(results)
                .contains(
                        OrganizationTenantPlacementResult.CONFLICT);

        var successfulMutationCount =
                results.stream()
                        .filter(result ->
                                result
                                        == OrganizationTenantPlacementResult.MOVED
                                        || result
                                        == OrganizationTenantPlacementResult.DETACHED)
                        .count();

        assertThat(successfulMutationCount)
                .isEqualTo(
                        1);

        var finalPlacement =
                repository.findByTenantId(
                        tenantId);

        if (results.contains(
                OrganizationTenantPlacementResult.MOVED)) {

            assertThat(finalPlacement)
                    .get()
                    .extracting(
                            placement ->
                                    placement.organizationId())
                    .isEqualTo(
                            destination);

        } else {

            assertThat(finalPlacement)
                    .isEmpty();
        }
    }

    private Set<OrganizationTenantPlacementResult> executeConcurrently(
            Callable<OrganizationTenantPlacementResult> first,
            Callable<OrganizationTenantPlacementResult> second) {

        var barrier =
                new CyclicBarrier(
                        2);

        try (var executor =
                Executors.newFixedThreadPool(
                        2)) {

            var firstFuture =
                    executor.submit(() -> {
                        barrier.await(
                                10,
                                TimeUnit.SECONDS);

                        return first.call();
                    });

            var secondFuture =
                    executor.submit(() -> {
                        barrier.await(
                                10,
                                TimeUnit.SECONDS);

                        return second.call();
                    });

            var firstResult =
                    firstFuture.get(
                            15,
                            TimeUnit.SECONDS);

            var secondResult =
                    secondFuture.get(
                            15,
                            TimeUnit.SECONDS);

            return Set.of(
                    firstResult,
                    secondResult);

        } catch (Exception exception) {

            throw new AssertionError(
                    "Concurrent placement operation did not complete within "
                            + Duration.ofSeconds(15),
                    exception);
        }
    }

    private UUID seedOrganization(
            String name) {

        var organizationId =
                UUID.randomUUID();

        jdbcTemplate.update(
                """
                INSERT INTO organizations.organizations (
                    id,
                    name,
                    status
                )
                VALUES (?, ?, 'ACTIVE')
                """,
                organizationId,
                name);

        return organizationId;
    }

    private int countPlacements(
            UUID tenantId) {

        return jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM organizations.tenant_placements
                WHERE tenant_id = ?
                """,
                Integer.class,
                tenantId);
    }
}

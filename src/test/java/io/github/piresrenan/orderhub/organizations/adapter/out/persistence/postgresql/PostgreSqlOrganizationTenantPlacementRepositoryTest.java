package io.github.piresrenan.orderhub.organizations.adapter.out.persistence.postgresql;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

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
class PostgreSqlOrganizationTenantPlacementRepositoryTest {

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
    private static DataSourceTransactionManager transactionManager;

    private PostgreSqlOrganizationTenantPlacementRepository repository;

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

        transactionManager =
                new DataSourceTransactionManager(
                        dataSource);
    }

    @BeforeEach
    void prepareRepository() {

        jdbcTemplate.update(
                "TRUNCATE TABLE organizations.tenant_placements");

        jdbcTemplate.update(
                "TRUNCATE TABLE organizations.organizations CASCADE");

        repository =
                new PostgreSqlOrganizationTenantPlacementRepository(
                        jdbcTemplate,
                        transactionManager);
    }

    @Test
    void attachesUnassignedTenantToActiveOrganization() {

        var tenantId =
                UUID.randomUUID();

        var organizationId =
                seedOrganization(
                        "Active Organization",
                        "ACTIVE");

        var result =
                repository.attach(
                        tenantId,
                        organizationId);

        assertThat(result)
                .isEqualTo(
                        OrganizationTenantPlacementResult.ATTACHED);

        assertThat(
                repository.findByTenantId(
                        tenantId))
                .get()
                .satisfies(placement -> {
                    assertThat(placement.tenantId())
                            .isEqualTo(tenantId);

                    assertThat(placement.organizationId())
                            .isEqualTo(organizationId);
                });
    }

    @Test
    void attachingSameTenantToSameOrganizationIsIdempotent() {

        var tenantId =
                UUID.randomUUID();

        var organizationId =
                seedOrganization(
                        "Active Organization",
                        "ACTIVE");

        assertThat(
                repository.attach(
                        tenantId,
                        organizationId))
                .isEqualTo(
                        OrganizationTenantPlacementResult.ATTACHED);

        assertThat(
                repository.attach(
                        tenantId,
                        organizationId))
                .isEqualTo(
                        OrganizationTenantPlacementResult.ALREADY_ATTACHED);
    }

    @Test
    void attachingTenantAlreadyPlacedElsewhereConflicts() {

        var tenantId =
                UUID.randomUUID();

        var organizationA =
                seedOrganization(
                        "Organization A",
                        "ACTIVE");

        var organizationB =
                seedOrganization(
                        "Organization B",
                        "ACTIVE");

        repository.attach(
                tenantId,
                organizationA);

        assertThat(
                repository.attach(
                        tenantId,
                        organizationB))
                .isEqualTo(
                        OrganizationTenantPlacementResult.CONFLICT);

        assertThat(
                repository.findByTenantId(
                        tenantId))
                .get()
                .extracting(
                        placement ->
                                placement.organizationId())
                .isEqualTo(
                        organizationA);
    }

    @Test
    void attachRejectsMissingDestinationOrganization() {

        var tenantId =
                UUID.randomUUID();

        assertThat(
                repository.attach(
                        tenantId,
                        UUID.randomUUID()))
                .isEqualTo(
                        OrganizationTenantPlacementResult.DESTINATION_NOT_FOUND);

        assertThat(
                repository.findByTenantId(
                        tenantId))
                .isEmpty();
    }

    @Test
    void attachRejectsSuspendedDestinationOrganization() {

        var tenantId =
                UUID.randomUUID();

        var suspendedOrganization =
                seedOrganization(
                        "Suspended Organization",
                        "SUSPENDED");

        assertThat(
                repository.attach(
                        tenantId,
                        suspendedOrganization))
                .isEqualTo(
                        OrganizationTenantPlacementResult.DESTINATION_SUSPENDED);

        assertThat(
                repository.findByTenantId(
                        tenantId))
                .isEmpty();
    }

    @Test
    void movesOnlyFromExpectedSourceToActiveDestination() {

        var tenantId =
                UUID.randomUUID();

        var source =
                seedOrganization(
                        "Source",
                        "ACTIVE");

        var destination =
                seedOrganization(
                        "Destination",
                        "ACTIVE");

        repository.attach(
                tenantId,
                source);

        assertThat(
                repository.move(
                        tenantId,
                        source,
                        destination))
                .isEqualTo(
                        OrganizationTenantPlacementResult.MOVED);

        assertThat(
                repository.findByTenantId(
                        tenantId))
                .get()
                .extracting(
                        placement ->
                                placement.organizationId())
                .isEqualTo(
                        destination);
    }

    @Test
    void staleMoveConflictsWithoutOverwritingCurrentPlacement() {

        var tenantId =
                UUID.randomUUID();

        var original =
                seedOrganization(
                        "Original",
                        "ACTIVE");

        var current =
                seedOrganization(
                        "Current",
                        "ACTIVE");

        var staleDestination =
                seedOrganization(
                        "Stale Destination",
                        "ACTIVE");

        repository.attach(
                tenantId,
                original);

        repository.move(
                tenantId,
                original,
                current);

        assertThat(
                repository.move(
                        tenantId,
                        original,
                        staleDestination))
                .isEqualTo(
                        OrganizationTenantPlacementResult.CONFLICT);

        assertThat(
                repository.findByTenantId(
                        tenantId))
                .get()
                .extracting(
                        placement ->
                                placement.organizationId())
                .isEqualTo(
                        current);
    }

    @Test
    void moveRejectsSuspendedDestinationWithoutChangingPlacement() {

        var tenantId =
                UUID.randomUUID();

        var source =
                seedOrganization(
                        "Source",
                        "ACTIVE");

        var suspendedDestination =
                seedOrganization(
                        "Suspended Destination",
                        "SUSPENDED");

        repository.attach(
                tenantId,
                source);

        assertThat(
                repository.move(
                        tenantId,
                        source,
                        suspendedDestination))
                .isEqualTo(
                        OrganizationTenantPlacementResult.DESTINATION_SUSPENDED);

        assertThat(
                repository.findByTenantId(
                        tenantId))
                .get()
                .extracting(
                        placement ->
                                placement.organizationId())
                .isEqualTo(
                        source);
    }

    @Test
    void detachesOnlyFromExpectedOrganization() {

        var tenantId =
                UUID.randomUUID();

        var organizationId =
                seedOrganization(
                        "Organization",
                        "ACTIVE");

        repository.attach(
                tenantId,
                organizationId);

        assertThat(
                repository.detach(
                        tenantId,
                        organizationId))
                .isEqualTo(
                        OrganizationTenantPlacementResult.DETACHED);

        assertThat(
                repository.findByTenantId(
                        tenantId))
                .isEmpty();
    }

    @Test
    void detachIsIdempotentWhenTenantIsAlreadyUnassigned() {

        assertThat(
                repository.detach(
                        UUID.randomUUID(),
                        UUID.randomUUID()))
                .isEqualTo(
                        OrganizationTenantPlacementResult.ALREADY_UNASSIGNED);
    }

    @Test
    void staleDetachConflictsAndNeverRemovesNewerPlacement() {

        var tenantId =
                UUID.randomUUID();

        var original =
                seedOrganization(
                        "Original",
                        "ACTIVE");

        var current =
                seedOrganization(
                        "Current",
                        "ACTIVE");

        repository.attach(
                tenantId,
                original);

        repository.move(
                tenantId,
                original,
                current);

        assertThat(
                repository.detach(
                        tenantId,
                        original))
                .isEqualTo(
                        OrganizationTenantPlacementResult.CONFLICT);

        assertThat(
                repository.findByTenantId(
                        tenantId))
                .get()
                .extracting(
                        placement ->
                                placement.organizationId())
                .isEqualTo(
                        current);
    }

    private UUID seedOrganization(
            String name,
            String status) {

        var organizationId =
                UUID.randomUUID();

        jdbcTemplate.update(
                """
                INSERT INTO organizations.organizations (
                    id,
                    name,
                    status
                )
                VALUES (?, ?, ?)
                """,
                organizationId,
                name,
                status);

        return organizationId;
    }
}

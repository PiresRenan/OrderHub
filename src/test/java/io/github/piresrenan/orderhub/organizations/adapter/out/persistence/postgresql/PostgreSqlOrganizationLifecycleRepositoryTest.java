package io.github.piresrenan.orderhub.organizations.adapter.out.persistence.postgresql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

import io.github.piresrenan.orderhub.organizations.application.port.out.OrganizationLifecycleMutationResult;
import io.github.piresrenan.orderhub.organizations.domain.model.Organization;
import io.github.piresrenan.orderhub.organizations.domain.model.OrganizationStatus;

@Testcontainers
class PostgreSqlOrganizationLifecycleRepositoryTest {

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
    private static PostgreSqlOrganizationRepository organizationRepository;
    private static PostgreSqlOrganizationLifecycleRepository lifecycleRepository;

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

        organizationRepository =
                new PostgreSqlOrganizationRepository(
                        jdbcTemplate);

        lifecycleRepository =
                new PostgreSqlOrganizationLifecycleRepository(
                        jdbcTemplate,
                        new DataSourceTransactionManager(
                                dataSource));
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
    void suspendsActiveOrganization() {

        var organization =
                seedOrganization(
                        "Lifecycle Organization");

        assertThat(
                lifecycleRepository.setStatus(
                        organization.id(),
                        OrganizationStatus.SUSPENDED))
                .isEqualTo(
                        OrganizationLifecycleMutationResult.UPDATED);

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
    void suspendingSuspendedOrganizationIsIdempotent() {

        var organization =
                seedOrganization(
                        "Lifecycle Organization");

        assertThat(
                lifecycleRepository.setStatus(
                        organization.id(),
                        OrganizationStatus.SUSPENDED))
                .isEqualTo(
                        OrganizationLifecycleMutationResult.UPDATED);

        assertThat(
                lifecycleRepository.setStatus(
                        organization.id(),
                        OrganizationStatus.SUSPENDED))
                .isEqualTo(
                        OrganizationLifecycleMutationResult.ALREADY_DESIRED);
    }

    @Test
    void recoversSuspendedOrganization() {

        var organization =
                seedOrganization(
                        "Lifecycle Organization");

        lifecycleRepository.setStatus(
                organization.id(),
                OrganizationStatus.SUSPENDED);

        assertThat(
                lifecycleRepository.setStatus(
                        organization.id(),
                        OrganizationStatus.ACTIVE))
                .isEqualTo(
                        OrganizationLifecycleMutationResult.UPDATED);

        assertThat(
                organizationRepository.findById(
                        organization.id()))
                .get()
                .extracting(
                        persisted ->
                                persisted.status())
                .isEqualTo(
                        OrganizationStatus.ACTIVE);
    }

    @Test
    void recoveringActiveOrganizationIsIdempotent() {

        var organization =
                seedOrganization(
                        "Lifecycle Organization");

        assertThat(
                lifecycleRepository.setStatus(
                        organization.id(),
                        OrganizationStatus.ACTIVE))
                .isEqualTo(
                        OrganizationLifecycleMutationResult.ALREADY_DESIRED);
    }

    @Test
    void missingOrganizationReturnsNotFound() {

        assertThat(
                lifecycleRepository.setStatus(
                        UUID.randomUUID(),
                        OrganizationStatus.SUSPENDED))
                .isEqualTo(
                        OrganizationLifecycleMutationResult.NOT_FOUND);
    }

    @Test
    void rejectsMissingOrganizationIdentity() {

        assertThatThrownBy(() ->
                lifecycleRepository.setStatus(
                        null,
                        OrganizationStatus.SUSPENDED))
                .isInstanceOf(
                        IllegalArgumentException.class)
                .hasMessage(
                        "Organization lifecycle id is required");
    }

    @Test
    void rejectsMissingDesiredStatus() {

        assertThatThrownBy(() ->
                lifecycleRepository.setStatus(
                        UUID.randomUUID(),
                        null))
                .isInstanceOf(
                        IllegalArgumentException.class)
                .hasMessage(
                        "Organization desired lifecycle status is required");
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
}

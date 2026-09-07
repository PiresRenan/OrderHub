package io.github.piresrenan.orderhub.organizations.adapter.out.persistence.postgresql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import io.github.piresrenan.orderhub.organizations.application.port.out.OrganizationPersistenceException;
import io.github.piresrenan.orderhub.organizations.domain.model.Organization;
import io.github.piresrenan.orderhub.organizations.domain.model.OrganizationStatus;

@Testcontainers
class PostgreSqlOrganizationRepositoryTest {

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

    private PostgreSqlOrganizationRepository repository;

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

    @BeforeEach
    void prepareRepository() {

        repository =
                new PostgreSqlOrganizationRepository(
                        jdbcTemplate);
    }

    @Test
    void savesAndReconstructsActiveOrganization() {

        var id =
                UUID.randomUUID();

        var organization =
                Organization.create(
                        id,
                        "  Acme Group  ");

        var saved =
                repository.save(
                        organization);

        var loaded =
                repository.findById(
                        id)
                        .orElseThrow();

        assertThat(saved)
                .isSameAs(
                        organization);

        assertThat(loaded.id())
                .isEqualTo(
                        id);

        assertThat(loaded.name())
                .isEqualTo(
                        "Acme Group");

        assertThat(loaded.status())
                .isEqualTo(
                        OrganizationStatus.ACTIVE);
    }

    @Test
    void reconstructsSuspendedOrganizationWithoutNormalizingPersistedState() {

        var id =
                UUID.randomUUID();

        var organization =
                Organization.rehydrate(
                        id,
                        "Suspended Group",
                        OrganizationStatus.SUSPENDED);

        repository.save(
                organization);

        var loaded =
                repository.findById(
                        id)
                        .orElseThrow();

        assertThat(loaded.status())
                .isEqualTo(
                        OrganizationStatus.SUSPENDED);

        assertThat(loaded.name())
                .isEqualTo(
                        "Suspended Group");
    }

    @Test
    void returnsEmptyWhenOrganizationDoesNotExist() {

        assertThat(
                repository.findById(
                        UUID.randomUUID()))
                .isEmpty();
    }

    @Test
    void translatesDuplicateIdentityFailureAtApplicationBoundary() {

        var id =
                UUID.randomUUID();

        repository.save(
                Organization.create(
                        id,
                        "Existing Organization"));

        assertThatThrownBy(() ->
                repository.save(
                        Organization.create(
                                id,
                                "Conflicting Organization")))
                .isInstanceOf(
                        OrganizationPersistenceException.class)
                .hasMessage(
                        "Organization persistence operation failed.")
                .hasCauseInstanceOf(
                        DataAccessException.class);
    }

    @Test
    void persistenceFailureMessageDoesNotExposeOrganizationState() {

        var id =
                UUID.fromString(
                        "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

        var privateName =
                "Synthetic Private Organization";

        repository.save(
                Organization.create(
                        id,
                        "Existing Organization"));

        assertThatThrownBy(() ->
                repository.save(
                        Organization.create(
                                id,
                                privateName)))
                .isInstanceOf(
                        OrganizationPersistenceException.class)
                .satisfies(exception ->
                        assertThat(
                                exception.getMessage())
                                .isEqualTo(
                                        "Organization persistence operation failed.")
                                .doesNotContain(
                                        id.toString(),
                                        privateName,
                                        "INSERT",
                                        "JDBC",
                                        "PostgreSQL",
                                        "PSQLException"));
    }
}

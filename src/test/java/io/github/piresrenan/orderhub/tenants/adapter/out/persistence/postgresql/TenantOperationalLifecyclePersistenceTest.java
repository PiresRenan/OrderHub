package io.github.piresrenan.orderhub.tenants.adapter.out.persistence.postgresql;

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

import io.github.piresrenan.orderhub.tenants.domain.model.Tenant;
import io.github.piresrenan.orderhub.tenants.domain.model.TenantStatus;

@Testcontainers
class TenantOperationalLifecyclePersistenceTest {

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

    private PostgreSqlTenantRepository repository;

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

        jdbcTemplate.update(
                "TRUNCATE TABLE tenants.tenants");

        repository =
                new PostgreSqlTenantRepository(
                        jdbcTemplate);
    }

    @Test
    void persistsSuspendedTenantOperationalState() {

        var tenant =
                Tenant.create(
                                UUID.randomUUID(),
                                "Acme Commerce")
                        .suspend();

        repository.save(tenant);

        var loaded =
                repository.findById(
                                tenant.id())
                        .orElseThrow();

        assertThat(loaded.status())
                .isEqualTo(TenantStatus.SUSPENDED);

        var storedStatus =
                jdbcTemplate.queryForObject(
                        """
                        SELECT status
                        FROM tenants.tenants
                        WHERE id = ?
                        """,
                        String.class,
                        tenant.id());

        assertThat(storedStatus)
                .isEqualTo("SUSPENDED");
    }

    @Test
    void databaseRequiresTenantOperationalStatus() {

        assertThatThrownBy(() ->
                jdbcTemplate.update(
                        """
                        INSERT INTO tenants.tenants (
                            id,
                            name
                        )
                        VALUES (?, ?)
                        """,
                        UUID.randomUUID(),
                        "Acme Commerce"))
                .isInstanceOf(
                        DataIntegrityViolationException.class);
    }

    @Test
    void databaseRejectsUnknownTenantOperationalStatus() {

        assertThatThrownBy(() ->
                jdbcTemplate.update(
                        """
                        INSERT INTO tenants.tenants (
                            id,
                            name,
                            status
                        )
                        VALUES (?, ?, ?)
                        """,
                        UUID.randomUUID(),
                        "Acme Commerce",
                        "UNKNOWN"))
                .isInstanceOf(
                        DataIntegrityViolationException.class);
    }
}

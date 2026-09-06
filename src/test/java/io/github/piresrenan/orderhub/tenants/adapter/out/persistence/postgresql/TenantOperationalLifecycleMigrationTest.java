package io.github.piresrenan.orderhub.tenants.adapter.out.persistence.postgresql;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class TenantOperationalLifecycleMigrationTest {

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

    @Test
    void upgradesExistingTenantToActiveOperationalState() {

        var dataSource =
                new DriverManagerDataSource(
                        POSTGRES.getJdbcUrl(),
                        POSTGRES.getUsername(),
                        POSTGRES.getPassword());

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("23"))
                .load()
                .migrate();

        var jdbcTemplate =
                new JdbcTemplate(dataSource);

        var tenantId =
                UUID.fromString(
                        "aaaaaaaa-aaaa-aaaa-aaaa-aaaaaaaaaaaa");

        jdbcTemplate.update(
                """
                INSERT INTO tenants.tenants (
                    id,
                    name
                )
                VALUES (?, ?)
                """,
                tenantId,
                "Existing Tenant");

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        var status =
                jdbcTemplate.queryForObject(
                        """
                        SELECT status
                        FROM tenants.tenants
                        WHERE id = ?
                        """,
                        String.class,
                        tenantId);

        assertThat(status)
                .isEqualTo("ACTIVE");

        var columnDefault =
                jdbcTemplate.queryForObject(
                        """
                        SELECT column_default
                        FROM information_schema.columns
                        WHERE table_schema = 'tenants'
                          AND table_name = 'tenants'
                          AND column_name = 'status'
                        """,
                        String.class);

        assertThat(columnDefault)
                .as(
                        "Future Tenant writes must provide"
                                + " operational state explicitly")
                .isNull();
    }
}

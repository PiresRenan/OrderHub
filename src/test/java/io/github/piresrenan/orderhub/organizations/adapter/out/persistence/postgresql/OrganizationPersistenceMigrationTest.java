package io.github.piresrenan.orderhub.organizations.adapter.out.persistence.postgresql;

import static org.assertj.core.api.Assertions.assertThat;

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
class OrganizationPersistenceMigrationTest {

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
    void upgradesV24DatabaseWithOrganizationPersistenceSchema() {

        var dataSource =
                new DriverManagerDataSource(
                        POSTGRES.getJdbcUrl(),
                        POSTGRES.getUsername(),
                        POSTGRES.getPassword());

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("24"))
                .load()
                .migrate();

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        var jdbcTemplate =
                new JdbcTemplate(dataSource);

        var tableCount =
                jdbcTemplate.queryForObject(
                        """
                        SELECT count(*)
                        FROM information_schema.tables
                        WHERE table_schema = 'organizations'
                          AND table_name = 'organizations'
                        """,
                        Integer.class);

        assertThat(tableCount)
                .isEqualTo(
                        1);
    }
}

package io.github.piresrenan.orderhub.authorization.adapter.out.persistence.postgresql;

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
class AdministrativeAuthorizationMigrationTest {

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
    void upgradesV26AuthorizationSchemaToAdministrativeAuthorization() {

        var dataSource =
                new DriverManagerDataSource(
                        POSTGRES.getJdbcUrl(),
                        POSTGRES.getUsername(),
                        POSTGRES.getPassword());

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .target(MigrationVersion.fromVersion("26"))
                .load()
                .migrate();

        var jdbcTemplate =
                new JdbcTemplate(
                        dataSource);

        assertThat(
                columnCount(
                        jdbcTemplate,
                        "permissions",
                        "administrative_scope"))
                .isZero();

        assertThat(
                tableCount(
                        jdbcTemplate,
                        "administrative_grants"))
                .isZero();

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        assertThat(
                columnCount(
                        jdbcTemplate,
                        "permissions",
                        "administrative_scope"))
                .isEqualTo(
                        1);

        assertThat(
                tableCount(
                        jdbcTemplate,
                        "administrative_grants"))
                .isEqualTo(
                        1);

        assertThat(
                jdbcTemplate.queryForObject(
                        """
                        SELECT count(*)
                        FROM access_control.permissions
                        WHERE administrative_scope IS NOT NULL
                        """,
                        Integer.class))
                .isEqualTo(
                        5);
    }

    private static int columnCount(
            JdbcTemplate jdbcTemplate,
            String table,
            String column) {

        return jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM information_schema.columns
                WHERE table_schema = 'access_control'
                  AND table_name = ?
                  AND column_name = ?
                """,
                Integer.class,
                table,
                column);
    }

    private static int tableCount(
            JdbcTemplate jdbcTemplate,
            String table) {

        return jdbcTemplate.queryForObject(
                """
                SELECT count(*)
                FROM information_schema.tables
                WHERE table_schema = 'access_control'
                  AND table_name = ?
                """,
                Integer.class,
                table);
    }
}

package io.github.piresrenan.orderhub.users.adapter.out.persistence.postgresql;

import static org.assertj.core.api.Assertions.assertThat;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class UserMigrationTest {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse(
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
    void migratesEmptyDatabaseIntoUsersSchema() {
        // Why: the complete database must be reconstructible from immutable Flyway
        // history as the Users module becomes durable.
        // Covers: cumulative V1, V2 and required V3 execution plus Users schema/table
        // creation.
        // Prevents: Users persistence depending on manual database preparation or
        // modification of accepted migrations.

        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword());

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        var jdbcTemplate = new JdbcTemplate(dataSource);

        var requiredMigrationsApplied = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM public.flyway_schema_history
                WHERE version IN ('1', '2', '3')
                  AND success = TRUE
                """,
                Integer.class);

        var usersSchemaExists = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                    FROM information_schema.schemata
                    WHERE schema_name = 'users'
                )
                """,
                Boolean.class);

        var usersTableExists = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                    FROM information_schema.tables
                    WHERE table_schema = 'users'
                      AND table_name = 'users'
                )
                """,
                Boolean.class);

        var membershipsTableExists = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                    FROM information_schema.tables
                    WHERE table_schema = 'users'
                      AND table_name = 'tenant_memberships'
                )
                """,
                Boolean.class);

        assertThat(requiredMigrationsApplied)
                .isEqualTo(3);

        assertThat(usersSchemaExists)
                .isTrue();

        assertThat(usersTableExists)
                .isTrue();

        assertThat(membershipsTableExists)
                .isTrue();
    }
}

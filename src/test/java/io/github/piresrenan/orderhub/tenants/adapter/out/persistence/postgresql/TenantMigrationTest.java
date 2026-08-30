package io.github.piresrenan.orderhub.tenants.adapter.out.persistence.postgresql;

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
class TenantMigrationTest {

    private static final DockerImageName POSTGRES_IMAGE = DockerImageName.parse(
            "postgres:18.6-trixie@sha256:"
                    + "4ef4dbc939d61acea57712655ddb4b4ab27419c913f94cca0cd57cb3ea3c2280")
            .asCompatibleSubstituteFor("postgres");

    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            POSTGRES_IMAGE)
            .withDatabaseName("orderhub_test")
            .withUsername("orderhub_test")
            .withPassword("synthetic-test-password");

    @Test
    void migratesEmptyDatabaseIntoOrdersAndTenantsSchemas() {
        // Why: a completely empty database must be reconstructible exclusively from
        // immutable Flyway history as new modules are added.
        // Covers: cumulative execution of accepted V1 plus the new Tenant V2 migration.
        // Prevents: Tenant persistence depending on manually created schemas or
        // accidentally replacing the already accepted Orders migration.

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
                WHERE version IN ('1', '2')
                  AND success = TRUE
                """, Integer.class);

        var ordersSchemaExists = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                    FROM information_schema.schemata
                    WHERE schema_name = 'orders'
                )
                """, Boolean.class);

        var tenantsSchemaExists = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                    FROM information_schema.schemata
                    WHERE schema_name = 'tenants'
                )
                """, Boolean.class);

        var tenantTableExists = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                    FROM information_schema.tables
                    WHERE table_schema = 'tenants'
                      AND table_name = 'tenants'
                )
                """, Boolean.class);

        assertThat(requiredMigrationsApplied)
                .isEqualTo(2);

        assertThat(ordersSchemaExists)
                .isTrue();

        assertThat(tenantsSchemaExists)
                .isTrue();

        assertThat(tenantTableExists)
                .isTrue();
    }
}

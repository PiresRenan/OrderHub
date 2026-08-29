package io.github.piresrenan.orderhub.orders.adapter.out.persistence.postgresql;

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
class PostgreSqlMigrationTest {

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
    void migratesEmptyDatabaseIntoCompleteOrdersSchema() {
        // Why: a clean environment must be able to reconstruct the complete Orders
        // schema without manual SQL or pre-existing database state.
        // Covers: PostgreSQL container compatibility, Flyway migration discovery and
        // creation of the two relations owned by the Orders module.
        // Prevents: deployments depending on developer-created tables, stale schemas
        // or an embedded database that differs from the production database engine.

        var dataSource = new DriverManagerDataSource(
                POSTGRES.getJdbcUrl(),
                POSTGRES.getUsername(),
                POSTGRES.getPassword());

        var migrationResult = Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        var jdbcTemplate = new JdbcTemplate(dataSource);

        var ordersSchemaExists = jdbcTemplate.queryForObject("""
                SELECT EXISTS (
                    SELECT 1
                    FROM information_schema.schemata
                    WHERE schema_name = 'orders'
                )
                """, Boolean.class);

        var ownedTableCount = jdbcTemplate.queryForObject("""
                SELECT COUNT(*)
                FROM information_schema.tables
                WHERE table_schema = 'orders'
                  AND table_name IN ('orders', 'order_items')
                """, Integer.class);

        assertThat(migrationResult.migrationsExecuted)
                .isEqualTo(1);

        assertThat(ordersSchemaExists)
                .isTrue();

        assertThat(ownedTableCount)
                .isEqualTo(2);
    }
}
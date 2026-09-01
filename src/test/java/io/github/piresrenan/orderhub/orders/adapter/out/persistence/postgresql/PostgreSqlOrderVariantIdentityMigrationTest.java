package io.github.piresrenan.orderhub.orders.adapter.out.persistence.postgresql;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Verifies the semantic migration from Product identity to sellable Variant
 * identity inside Orders.
 *
 * <p>
 * The test deliberately starts at V7, inserts data using the historical
 * product_id column, then applies the remaining migrations. This proves that
 * V8 evolves an existing deployment instead of only producing the desired
 * schema on an empty database.
 * </p>
 */
@Testcontainers
class PostgreSqlOrderVariantIdentityMigrationTest {

    private static final DockerImageName POSTGRES_IMAGE =
            DockerImageName.parse(
                    "postgres:18.6-trixie@sha256:"
                            + "4ef4dbc939d61acea57712655ddb4b4ab27419c913f94cca0cd57cb3ea3c2280")
                    .asCompatibleSubstituteFor("postgres");

    private static final UUID TENANT_ID =
            UUID.fromString(
                    "11111111-1111-1111-1111-111111111111");

    private static final UUID ORDER_ID =
            UUID.fromString(
                    "22222222-2222-2222-2222-222222222222");

    private static final UUID CUSTOMER_ID =
            UUID.fromString(
                    "33333333-3333-3333-3333-333333333333");

    private static final UUID VARIANT_ID =
            UUID.fromString(
                    "44444444-4444-4444-4444-444444444444");

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(POSTGRES_IMAGE)
                    .withDatabaseName("orderhub_test")
                    .withUsername("orderhub_test")
                    .withPassword("synthetic-test-password");

    @Test
    void renamesHistoricalProductIdentityToVariantWithoutLosingOrderItem() {

        var dataSource =
                new DriverManagerDataSource(
                        POSTGRES.getJdbcUrl(),
                        POSTGRES.getUsername(),
                        POSTGRES.getPassword());

        /*
         * Reconstruct the exact pre-V8 database shape.
         */
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .target("7")
                .load()
                .migrate();

        var jdbcTemplate =
                new JdbcTemplate(
                        dataSource);

        jdbcTemplate.update("""
                INSERT INTO orders.orders (
                    tenant_id,
                    id,
                    customer_id,
                    status
                )
                VALUES (?, ?, ?, 'CREATED')
                """,
                TENANT_ID,
                ORDER_ID,
                CUSTOMER_ID);

        jdbcTemplate.update("""
                INSERT INTO orders.order_items (
                    tenant_id,
                    order_id,
                    line_number,
                    product_id,
                    quantity
                )
                VALUES (?, ?, 0, ?, 3)
                """,
                TENANT_ID,
                ORDER_ID,
                VARIANT_ID);

        /*
         * Apply V8 and every future migration available to this test run.
         */
        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        var variantColumnExists =
                jdbcTemplate.queryForObject("""
                        SELECT EXISTS (
                            SELECT 1
                            FROM information_schema.columns
                            WHERE table_schema = 'orders'
                              AND table_name = 'order_items'
                              AND column_name = 'variant_id'
                        )
                        """,
                        Boolean.class);

        var historicalProductColumnExists =
                jdbcTemplate.queryForObject("""
                        SELECT EXISTS (
                            SELECT 1
                            FROM information_schema.columns
                            WHERE table_schema = 'orders'
                              AND table_name = 'order_items'
                              AND column_name = 'product_id'
                        )
                        """,
                        Boolean.class);

        var persistedVariantId =
                jdbcTemplate.queryForObject("""
                        SELECT variant_id
                        FROM orders.order_items
                        WHERE tenant_id = ?
                          AND order_id = ?
                          AND line_number = 0
                        """,
                        UUID.class,
                        TENANT_ID,
                        ORDER_ID);

        var persistedQuantity =
                jdbcTemplate.queryForObject("""
                        SELECT quantity
                        FROM orders.order_items
                        WHERE tenant_id = ?
                          AND order_id = ?
                          AND line_number = 0
                        """,
                        Integer.class,
                        TENANT_ID,
                        ORDER_ID);

        assertThat(variantColumnExists)
                .isTrue();

        assertThat(historicalProductColumnExists)
                .isFalse();

        assertThat(persistedVariantId)
                .isEqualTo(
                        VARIANT_ID);

        assertThat(persistedQuantity)
                .isEqualTo(3);
    }
}
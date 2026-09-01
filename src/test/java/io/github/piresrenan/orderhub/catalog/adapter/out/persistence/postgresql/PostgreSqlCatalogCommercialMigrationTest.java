package io.github.piresrenan.orderhub.catalog.adapter.out.persistence.postgresql;

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

@Testcontainers
class PostgreSqlCatalogCommercialMigrationTest {

    private static final DockerImageName POSTGRES_IMAGE =
            DockerImageName.parse(
                    "postgres:18.6-trixie@sha256:"
                            + "4ef4dbc939d61acea57712655ddb4b4ab27419c913f94cca0cd57cb3ea3c2280")
                    .asCompatibleSubstituteFor("postgres");

    private static final UUID TENANT_ID =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final UUID PRODUCT_ID =
            UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static final UUID VARIANT_ID =
            UUID.fromString("33333333-3333-3333-3333-333333333333");

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(POSTGRES_IMAGE)
                    .withDatabaseName("orderhub_test")
                    .withUsername("orderhub_test")
                    .withPassword("synthetic-test-password");

    @Test
    void upgradesHistoricalCatalogDataWithoutLosingCommercialMeaning() {

        var dataSource =
                new DriverManagerDataSource(
                        POSTGRES.getJdbcUrl(),
                        POSTGRES.getUsername(),
                        POSTGRES.getPassword());

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .target("8")
                .load()
                .migrate();

        var jdbcTemplate =
                new JdbcTemplate(dataSource);

        jdbcTemplate.update("""
                INSERT INTO catalog.products (
                    tenant_id,
                    id,
                    name,
                    slug,
                    description,
                    status
                )
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                TENANT_ID,
                PRODUCT_ID,
                "Historical Monitor",
                "historical-monitor",
                "Created before V9",
                "ACTIVE");

        jdbcTemplate.update("""
                INSERT INTO catalog.product_variants (
                    tenant_id,
                    id,
                    product_id,
                    sku
                )
                VALUES (?, ?, ?, ?)
                """,
                TENANT_ID,
                VARIANT_ID,
                PRODUCT_ID,
                "HISTORICAL-SKU");

        Flyway.configure()
                .dataSource(dataSource)
                .locations("classpath:db/migration")
                .load()
                .migrate();

        var productBrand =
                jdbcTemplate.queryForObject("""
                        SELECT brand
                        FROM catalog.products
                        WHERE tenant_id = ?
                          AND id = ?
                        """,
                        String.class,
                        TENANT_ID,
                        PRODUCT_ID);

        var variant =
                jdbcTemplate.queryForMap("""
                        SELECT
                            sku,
                            display_name,
                            gtin,
                            mpn,
                            status
                        FROM catalog.product_variants
                        WHERE tenant_id = ?
                          AND id = ?
                        """,
                        TENANT_ID,
                        VARIANT_ID);

        assertThat(productBrand)
                .isNull();

        assertThat(variant.get("sku"))
                .isEqualTo("HISTORICAL-SKU");

        assertThat(variant.get("display_name"))
                .isNull();

        assertThat(variant.get("gtin"))
                .isNull();

        assertThat(variant.get("mpn"))
                .isNull();

        assertThat(variant.get("status"))
                .isEqualTo("ACTIVE");

        assertThat(tableExists(jdbcTemplate, "product_variant_attributes"))
                .isTrue();

        assertThat(tableExists(jdbcTemplate, "variant_base_prices"))
                .isTrue();

        assertThat(tableExists(jdbcTemplate, "media"))
                .isTrue();

        var successfulV9 =
                jdbcTemplate.queryForObject("""
                        SELECT COUNT(*)
                        FROM flyway_schema_history
                        WHERE version = '9'
                          AND success = TRUE
                        """,
                        Integer.class);

        assertThat(successfulV9)
                .isEqualTo(1);
    }

    private static boolean tableExists(
            JdbcTemplate jdbcTemplate,
            String tableName) {

        return Boolean.TRUE.equals(
                jdbcTemplate.queryForObject("""
                        SELECT EXISTS (
                            SELECT 1
                            FROM information_schema.tables
                            WHERE table_schema = 'catalog'
                              AND table_name = ?
                        )
                        """,
                        Boolean.class,
                        tableName));
    }
}
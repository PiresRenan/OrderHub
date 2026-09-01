package io.github.piresrenan.orderhub.catalog.adapter.out.persistence.postgresql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.postgresql.util.PSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@Testcontainers
class PostgreSqlCatalogCommercialSchemaConstraintsTest {

    private static final DockerImageName POSTGRES_IMAGE =
            DockerImageName.parse(
                    "postgres:18.6-trixie@sha256:"
                            + "4ef4dbc939d61acea57712655ddb4b4ab27419c913f94cca0cd57cb3ea3c2280")
                    .asCompatibleSubstituteFor("postgres");

    private static final UUID TENANT_A =
            UUID.fromString("11111111-1111-1111-1111-111111111111");

    private static final UUID TENANT_B =
            UUID.fromString("22222222-2222-2222-2222-222222222222");

    private static final UUID PRODUCT_ID =
            UUID.fromString("33333333-3333-3333-3333-333333333333");

    private static final UUID VARIANT_ID =
            UUID.fromString("44444444-4444-4444-4444-444444444444");

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(POSTGRES_IMAGE)
                    .withDatabaseName("orderhub_test")
                    .withUsername("orderhub_test")
                    .withPassword("synthetic-test-password");

    private static JdbcTemplate jdbcTemplate;

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
    void cleanCatalogData() {

        var commercialSchemaExists =
                Boolean.TRUE.equals(
                        jdbcTemplate.queryForObject(
                                "SELECT to_regclass('catalog.media') IS NOT NULL",
                                Boolean.class));

        if (commercialSchemaExists) {
            jdbcTemplate.update("""
                    TRUNCATE TABLE
                        catalog.media,
                        catalog.variant_base_prices,
                        catalog.product_variant_attributes,
                        catalog.product_categories,
                        catalog.product_variants,
                        catalog.categories,
                        catalog.products
                    """);
            return;
        }

        jdbcTemplate.update("""
                TRUNCATE TABLE
                    catalog.product_categories,
                    catalog.product_variants,
                    catalog.categories,
                    catalog.products
                """);
    }

    @Test
    void persistsCommercialProductAndVariantColumns() {

        insertProduct(TENANT_A, PRODUCT_ID, "Framework");

        insertVariant(
                TENANT_A,
                VARIANT_ID,
                PRODUCT_ID,
                "SKU-001",
                "Notebook / Black",
                "4006381333931",
                "Part.No/A-01",
                "ACTIVE");

        var productBrand =
                jdbcTemplate.queryForObject("""
                        SELECT brand
                        FROM catalog.products
                        WHERE tenant_id = ?
                          AND id = ?
                        """,
                        String.class,
                        TENANT_A,
                        PRODUCT_ID);

        var variant =
                jdbcTemplate.queryForMap("""
                        SELECT display_name, gtin, mpn, status
                        FROM catalog.product_variants
                        WHERE tenant_id = ?
                          AND id = ?
                        """,
                        TENANT_A,
                        VARIANT_ID);

        assertThat(productBrand).isEqualTo("Framework");
        assertThat(variant.get("display_name")).isEqualTo("Notebook / Black");
        assertThat(variant.get("gtin")).isEqualTo("4006381333931");
        assertThat(variant.get("mpn")).isEqualTo("Part.No/A-01");
        assertThat(variant.get("status")).isEqualTo("ACTIVE");
    }

    @Test
    void rejectsNonCanonicalProductBrand() {

        assertThatThrownBy(() ->
                insertProduct(TENANT_A, PRODUCT_ID, " Framework "))
                .satisfies(exception -> assertPostgresFailure(
                        exception,
                        "23514",
                        "ck_catalog_products_brand"));

        assertThatThrownBy(() ->
                insertProduct(TENANT_A, PRODUCT_ID, "Framework\nBrand"))
                .satisfies(exception -> assertPostgresFailure(
                        exception,
                        "23514",
                        "ck_catalog_products_brand"));
    }

    @Test
    void rejectsUnsupportedVariantStatus() {

        insertProduct(TENANT_A, PRODUCT_ID, null);

        assertThatThrownBy(() ->
                insertVariant(
                        TENANT_A,
                        VARIANT_ID,
                        PRODUCT_ID,
                        "SKU-001",
                        null,
                        null,
                        null,
                        "DELETED"))
                .satisfies(exception -> assertPostgresFailure(
                        exception,
                        "23514",
                        "ck_catalog_product_variants_status"));
    }

    @Test
    void rejectsNonCanonicalVariantDisplayNameAndMpn() {

        insertProduct(TENANT_A, PRODUCT_ID, null);

        assertThatThrownBy(() ->
                insertVariant(
                        TENANT_A, VARIANT_ID, PRODUCT_ID, "SKU-001",
                        " Variant ", null, null, "DRAFT"))
                .satisfies(exception -> assertPostgresFailure(
                        exception,
                        "23514",
                        "ck_catalog_product_variants_display_name"));

        assertThatThrownBy(() ->
                insertVariant(
                        TENANT_A, UUID.randomUUID(), PRODUCT_ID, "SKU-002",
                        null, null, " MPN ", "DRAFT"))
                .satisfies(exception -> assertPostgresFailure(
                        exception,
                        "23514",
                        "ck_catalog_product_variants_mpn"));
    }

    @Test
    void rejectsStructurallyInvalidOrBadCheckDigitGtin() {

        insertProduct(TENANT_A, PRODUCT_ID, null);

        var invalidGtins = new String[] {
                "1234567",
                "40063813339A1",
                "4006381333932"
        };

        for (var gtin : invalidGtins) {
            assertThatThrownBy(() ->
                    insertVariant(
                            TENANT_A,
                            UUID.randomUUID(),
                            PRODUCT_ID,
                            "SKU-" + UUID.randomUUID(),
                            null,
                            gtin,
                            null,
                            "DRAFT"))
                    .satisfies(exception -> assertPostgresFailure(
                            exception,
                            "23514",
                            "ck_catalog_product_variants_gtin"));
        }
    }

    @Test
    void enforcesVariantAttributeKeyValueAndCaseSensitiveUniqueness() {

        insertProduct(TENANT_A, PRODUCT_ID, null);
        insertVariant(TENANT_A, VARIANT_ID, PRODUCT_ID, "SKU-001", null, null, null, "DRAFT");

        insertAttribute(TENANT_A, VARIANT_ID, "color", "black");
        insertAttribute(TENANT_A, VARIANT_ID, "Color", "display-label");

        assertThatThrownBy(() ->
                insertAttribute(TENANT_A, VARIANT_ID, "color", "white"))
                .satisfies(exception -> assertPostgresFailure(
                        exception,
                        "23505",
                        "pk_catalog_product_variant_attributes"));

        assertThatThrownBy(() ->
                insertAttribute(TENANT_A, VARIANT_ID, "1color", "black"))
                .satisfies(exception -> assertPostgresFailure(
                        exception,
                        "23514",
                        "ck_catalog_product_variant_attributes_key"));

        assertThatThrownBy(() ->
                insertAttribute(TENANT_A, VARIANT_ID, "size", " large "))
                .satisfies(exception -> assertPostgresFailure(
                        exception,
                        "23514",
                        "ck_catalog_product_variant_attributes_value"));
    }

    @Test
    void variantAttributesCannotCrossTenantBoundary() {

        insertProduct(TENANT_A, PRODUCT_ID, null);
        insertVariant(TENANT_A, VARIANT_ID, PRODUCT_ID, "SKU-001", null, null, null, "DRAFT");

        assertThatThrownBy(() ->
                insertAttribute(TENANT_B, VARIANT_ID, "color", "black"))
                .satisfies(exception -> assertPostgresFailure(
                        exception,
                        "23503",
                        "fk_catalog_product_variant_attributes_variant"));
    }

    @Test
    void enforcesExactTenantScopedVariantBasePrices() {

        insertProduct(TENANT_A, PRODUCT_ID, null);
        insertVariant(TENANT_A, VARIANT_ID, PRODUCT_ID, "SKU-001", null, null, null, "ACTIVE");

        insertBasePrice(TENANT_A, VARIANT_ID, "BRL", 19_990);
        insertBasePrice(TENANT_A, VARIANT_ID, "USD", 3_999);

        assertThatThrownBy(() ->
                insertBasePrice(TENANT_A, VARIANT_ID, "BRL", 20_000))
                .satisfies(exception -> assertPostgresFailure(
                        exception,
                        "23505",
                        "pk_catalog_variant_base_prices"));

        assertThatThrownBy(() ->
                insertBasePrice(TENANT_A, VARIANT_ID, "brl", 20_000))
                .satisfies(exception -> assertPostgresFailure(
                        exception,
                        "23514",
                        "ck_catalog_variant_base_prices_currency"));

        assertThatThrownBy(() ->
                insertBasePrice(TENANT_A, VARIANT_ID, "EUR", -1))
                .satisfies(exception -> assertPostgresFailure(
                        exception,
                        "23514",
                        "ck_catalog_variant_base_prices_minor_units"));

        assertThatThrownBy(() ->
                insertBasePrice(TENANT_B, VARIANT_ID, "EUR", 100))
                .satisfies(exception -> assertPostgresFailure(
                        exception,
                        "23503",
                        "fk_catalog_variant_base_prices_variant"));
    }

    @Test
    void enforcesExactlyOneTenantSafeMediaOwner() {

        insertProduct(TENANT_A, PRODUCT_ID, null);
        insertVariant(TENANT_A, VARIANT_ID, PRODUCT_ID, "SKU-001", null, null, null, "ACTIVE");

        insertMedia(
                TENANT_A, UUID.randomUUID(), PRODUCT_ID, null,
                "IMAGE", "product-main", null, 0, false);

        insertMedia(
                TENANT_A, UUID.randomUUID(), null, VARIANT_ID,
                "VIDEO", "variant-video", null, 0, false);

        assertThatThrownBy(() ->
                insertMedia(
                        TENANT_A, UUID.randomUUID(), null, null,
                        "IMAGE", "orphan", null, 0, false))
                .satisfies(exception -> assertPostgresFailure(
                        exception,
                        "23514",
                        "ck_catalog_media_owner"));

        assertThatThrownBy(() ->
                insertMedia(
                        TENANT_A, UUID.randomUUID(), PRODUCT_ID, VARIANT_ID,
                        "IMAGE", "double-owner", null, 0, false))
                .satisfies(exception -> assertPostgresFailure(
                        exception,
                        "23514",
                        "ck_catalog_media_owner"));

        assertThatThrownBy(() ->
                insertMedia(
                        TENANT_B, UUID.randomUUID(), PRODUCT_ID, null,
                        "IMAGE", "cross-tenant", null, 0, false))
                .satisfies(exception -> assertPostgresFailure(
                        exception,
                        "23503",
                        "fk_catalog_media_product"));
    }

    @Test
    void enforcesMediaMetadataConstraints() {

        insertProduct(TENANT_A, PRODUCT_ID, null);

        assertThatThrownBy(() ->
                insertMedia(
                        TENANT_A, UUID.randomUUID(), PRODUCT_ID, null,
                        "EXECUTABLE", "ref", null, 0, false))
                .satisfies(exception -> assertPostgresFailure(
                        exception,
                        "23514",
                        "ck_catalog_media_type"));

        assertThatThrownBy(() ->
                insertMedia(
                        TENANT_A, UUID.randomUUID(), PRODUCT_ID, null,
                        "IMAGE", " ref ", null, 0, false))
                .satisfies(exception -> assertPostgresFailure(
                        exception,
                        "23514",
                        "ck_catalog_media_reference"));

        assertThatThrownBy(() ->
                insertMedia(
                        TENANT_A, UUID.randomUUID(), PRODUCT_ID, null,
                        "IMAGE", "ref", " alt ", 0, false))
                .satisfies(exception -> assertPostgresFailure(
                        exception,
                        "23514",
                        "ck_catalog_media_alt_text"));

        assertThatThrownBy(() ->
                insertMedia(
                        TENANT_A, UUID.randomUUID(), PRODUCT_ID, null,
                        "IMAGE", "ref", null, -1, false))
                .satisfies(exception -> assertPostgresFailure(
                        exception,
                        "23514",
                        "ck_catalog_media_sort_order"));
    }

    @Test
    void permitsAtMostOnePrimaryMediaPerOwner() {

        insertProduct(TENANT_A, PRODUCT_ID, null);
        insertVariant(TENANT_A, VARIANT_ID, PRODUCT_ID, "SKU-001", null, null, null, "ACTIVE");

        insertMedia(
                TENANT_A, UUID.randomUUID(), PRODUCT_ID, null,
                "IMAGE", "product-primary", null, 0, true);

        assertThatThrownBy(() ->
                insertMedia(
                        TENANT_A, UUID.randomUUID(), PRODUCT_ID, null,
                        "IMAGE", "product-primary-2", null, 1, true))
                .satisfies(exception -> assertPostgresFailure(
                        exception,
                        "23505",
                        "uq_catalog_media_primary_product"));

        insertMedia(
                TENANT_A, UUID.randomUUID(), null, VARIANT_ID,
                "IMAGE", "variant-primary", null, 0, true);

        assertThatThrownBy(() ->
                insertMedia(
                        TENANT_A, UUID.randomUUID(), null, VARIANT_ID,
                        "IMAGE", "variant-primary-2", null, 1, true))
                .satisfies(exception -> assertPostgresFailure(
                        exception,
                        "23505",
                        "uq_catalog_media_primary_variant"));

        insertMedia(
                TENANT_A, UUID.randomUUID(), PRODUCT_ID, null,
                "IMAGE", "product-secondary-1", null, 2, false);

        insertMedia(
                TENANT_A, UUID.randomUUID(), PRODUCT_ID, null,
                "IMAGE", "product-secondary-2", null, 3, false);
    }

    private static void insertProduct(
            UUID tenantId,
            UUID productId,
            String brand) {

        jdbcTemplate.update("""
                INSERT INTO catalog.products (
                    tenant_id,
                    id,
                    name,
                    slug,
                    description,
                    brand,
                    status
                )
                VALUES (?, ?, ?, ?, NULL, ?, 'DRAFT')
                """,
                tenantId,
                productId,
                "Product-" + productId,
                "p-" + productId.toString().replace("-", ""),
                brand);
    }

    private static void insertVariant(
            UUID tenantId,
            UUID variantId,
            UUID productId,
            String sku,
            String displayName,
            String gtin,
            String mpn,
            String status) {

        jdbcTemplate.update("""
                INSERT INTO catalog.product_variants (
                    tenant_id,
                    id,
                    product_id,
                    sku,
                    display_name,
                    gtin,
                    mpn,
                    status
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """,
                tenantId,
                variantId,
                productId,
                sku,
                displayName,
                gtin,
                mpn,
                status);
    }

    private static void insertAttribute(
            UUID tenantId,
            UUID variantId,
            String key,
            String value) {

        jdbcTemplate.update("""
                INSERT INTO catalog.product_variant_attributes (
                    tenant_id,
                    variant_id,
                    attribute_key,
                    attribute_value
                )
                VALUES (?, ?, ?, ?)
                """,
                tenantId,
                variantId,
                key,
                value);
    }

    private static void insertBasePrice(
            UUID tenantId,
            UUID variantId,
            String currencyCode,
            long minorUnits) {

        jdbcTemplate.update("""
                INSERT INTO catalog.variant_base_prices (
                    tenant_id,
                    variant_id,
                    currency_code,
                    minor_units
                )
                VALUES (?, ?, ?, ?)
                """,
                tenantId,
                variantId,
                currencyCode,
                minorUnits);
    }

    private static void insertMedia(
            UUID tenantId,
            UUID mediaId,
            UUID productId,
            UUID variantId,
            String mediaType,
            String reference,
            String altText,
            int sortOrder,
            boolean primary) {

        jdbcTemplate.update("""
                INSERT INTO catalog.media (
                    tenant_id,
                    id,
                    product_id,
                    variant_id,
                    media_type,
                    reference,
                    alt_text,
                    sort_order,
                    is_primary
                )
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
                """,
                tenantId,
                mediaId,
                productId,
                variantId,
                mediaType,
                reference,
                altText,
                sortOrder,
                primary);
    }

    private static void assertPostgresFailure(
            Throwable exception,
            String expectedSqlState,
            String expectedConstraint) {

        Throwable current = exception;

        while (current != null && !(current instanceof PSQLException)) {
            current = current.getCause();
        }

        assertThat(current)
                .isInstanceOf(PSQLException.class);

        var postgresException =
                (PSQLException) current;

        assertThat(postgresException.getSQLState())
                .isEqualTo(expectedSqlState);

        if (expectedConstraint != null) {
            assertThat(postgresException.getServerErrorMessage())
                    .isNotNull();

            assertThat(postgresException.getServerErrorMessage().getConstraint())
                    .isEqualTo(expectedConstraint);
        }
    }
}
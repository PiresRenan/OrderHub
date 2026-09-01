package io.github.piresrenan.orderhub.catalog.adapter.out.persistence.postgresql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.postgresql.util.PSQLException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Verifies Catalog relational invariants directly against PostgreSQL.
 *
 * <p>
 * These tests intentionally bypass future Catalog repositories. Their purpose is
 * to prove that storage itself preserves tenant isolation, aggregate ownership,
 * relational hierarchy and persistence invariants even when application/domain
 * validation is bypassed.
 * </p>
 */
@Testcontainers
class PostgreSqlCatalogSchemaConstraintsTest {

    private static final DockerImageName POSTGRES_IMAGE =
            DockerImageName.parse(
                    "postgres:18.6-trixie@sha256:"
                            + "4ef4dbc939d61acea57712655ddb4b4ab27419c913f94cca0cd57cb3ea3c2280")
                    .asCompatibleSubstituteFor("postgres");

    private static final UUID TENANT_A =
            UUID.fromString(
                    "11111111-1111-1111-1111-111111111111");

    private static final UUID TENANT_B =
            UUID.fromString(
                    "22222222-2222-2222-2222-222222222222");

    private static final UUID PRODUCT_ID =
            UUID.fromString(
                    "33333333-3333-3333-3333-333333333333");

    private static final UUID VARIANT_ID =
            UUID.fromString(
                    "44444444-4444-4444-4444-444444444444");

    private static final UUID CATEGORY_ID =
            UUID.fromString(
                    "55555555-5555-5555-5555-555555555555");

    private static final UUID CHILD_CATEGORY_ID =
            UUID.fromString(
                    "66666666-6666-6666-6666-666666666666");

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
    }

    // -------------------------------------------------------------------------
    // Product
    // -------------------------------------------------------------------------

    @Test
    void allowsSameProductIdAcrossDifferentTenants() {

        insertProduct(
                TENANT_A,
                PRODUCT_ID,
                "Monitor A",
                "monitor-a",
                "DRAFT");

        insertProduct(
                TENANT_B,
                PRODUCT_ID,
                "Monitor B",
                "monitor-b",
                "DRAFT");

        var count =
                jdbcTemplate.queryForObject("""
                        SELECT COUNT(*)
                        FROM catalog.products
                        WHERE id = ?
                        """,
                        Integer.class,
                        PRODUCT_ID);

        assertThat(count)
                .isEqualTo(2);
    }

    @Test
    void rejectsDuplicateProductIdWithinTenant() {

        insertProduct(
                TENANT_A,
                PRODUCT_ID,
                "Monitor",
                "monitor-a",
                "DRAFT");

        assertThatThrownBy(() ->
                insertProduct(
                        TENANT_A,
                        PRODUCT_ID,
                        "Another Monitor",
                        "monitor-b",
                        "DRAFT"))
                .satisfies(exception -> assertPostgresFailure(
                        exception,
                        "23505",
                        "pk_catalog_products"));
    }

    @Test
    void rejectsDuplicateProductSlugWithinTenant() {

        insertProduct(
                TENANT_A,
                PRODUCT_ID,
                "Monitor",
                "professional-monitor",
                "DRAFT");

        assertThatThrownBy(() ->
                insertProduct(
                        TENANT_A,
                        UUID.randomUUID(),
                        "Different Product",
                        "professional-monitor",
                        "DRAFT"))
                .satisfies(exception -> assertPostgresFailure(
                        exception,
                        "23505",
                        "uq_catalog_products_tenant_slug"));
    }

    @Test
    void allowsSameProductSlugAcrossDifferentTenants() {

        insertProduct(
                TENANT_A,
                UUID.randomUUID(),
                "Monitor A",
                "professional-monitor",
                "DRAFT");

        insertProduct(
                TENANT_B,
                UUID.randomUUID(),
                "Monitor B",
                "professional-monitor",
                "DRAFT");
    }

    @Test
    void rejectsInvalidProductSlugWhenDomainIsBypassed() {

        assertThatThrownBy(() ->
                insertProduct(
                        TENANT_A,
                        PRODUCT_ID,
                        "Monitor",
                        "professional monitor",
                        "DRAFT"))
                .satisfies(exception -> assertPostgresFailure(
                        exception,
                        "23514",
                        "ck_catalog_products_slug"));
    }

    @Test
    void rejectsUnsupportedProductStatus() {

        assertThatThrownBy(() ->
                insertProduct(
                        TENANT_A,
                        PRODUCT_ID,
                        "Monitor",
                        "professional-monitor",
                        "DELETED"))
                .satisfies(exception -> assertPostgresFailure(
                        exception,
                        "23514",
                        "ck_catalog_products_status"));
    }

    @Test
    void rejectsEmptyProductName() {

        assertThatThrownBy(() ->
                insertProduct(
                        TENANT_A,
                        PRODUCT_ID,
                        "",
                        "professional-monitor",
                        "DRAFT"))
                .satisfies(exception -> assertPostgresFailure(
                        exception,
                        "23514",
                        "ck_catalog_products_name"));
    }

    // -------------------------------------------------------------------------
    // ProductVariant
    // -------------------------------------------------------------------------

    @Test
    void allowsSameVariantIdAcrossDifferentTenants() {

        insertProduct(
                TENANT_A,
                PRODUCT_ID,
                "Monitor A",
                "monitor-a",
                "DRAFT");

        insertProduct(
                TENANT_B,
                PRODUCT_ID,
                "Monitor B",
                "monitor-b",
                "DRAFT");

        insertVariant(
                TENANT_A,
                VARIANT_ID,
                PRODUCT_ID,
                "SKU-A");

        insertVariant(
                TENANT_B,
                VARIANT_ID,
                PRODUCT_ID,
                "SKU-B");
    }

    @Test
    void rejectsDuplicateVariantIdWithinTenant() {

        insertProduct(
                TENANT_A,
                PRODUCT_ID,
                "Monitor",
                "monitor",
                "DRAFT");

        insertVariant(
                TENANT_A,
                VARIANT_ID,
                PRODUCT_ID,
                "SKU-A");

        assertThatThrownBy(() ->
                insertVariant(
                        TENANT_A,
                        VARIANT_ID,
                        PRODUCT_ID,
                        "SKU-B"))
                .satisfies(exception -> assertPostgresFailure(
                        exception,
                        "23505",
                        "pk_catalog_product_variants"));
    }

    @Test
    void rejectsDuplicateSkuWithinTenant() {

        insertProduct(
                TENANT_A,
                PRODUCT_ID,
                "Monitor",
                "monitor",
                "DRAFT");

        insertVariant(
                TENANT_A,
                UUID.randomUUID(),
                PRODUCT_ID,
                "SKU-001");

        assertThatThrownBy(() ->
                insertVariant(
                        TENANT_A,
                        UUID.randomUUID(),
                        PRODUCT_ID,
                        "SKU-001"))
                .satisfies(exception -> assertPostgresFailure(
                        exception,
                        "23505",
                        "uq_catalog_product_variants_tenant_sku"));
    }

    @Test
    void allowsSameSkuAcrossDifferentTenants() {

        insertProduct(
                TENANT_A,
                PRODUCT_ID,
                "Monitor A",
                "monitor-a",
                "DRAFT");

        insertProduct(
                TENANT_B,
                PRODUCT_ID,
                "Monitor B",
                "monitor-b",
                "DRAFT");

        insertVariant(
                TENANT_A,
                UUID.randomUUID(),
                PRODUCT_ID,
                "SKU-001");

        insertVariant(
                TENANT_B,
                UUID.randomUUID(),
                PRODUCT_ID,
                "SKU-001");
    }

    @Test
    void rejectsVariantWithoutOwningProduct() {

        assertThatThrownBy(() ->
                insertVariant(
                        TENANT_A,
                        VARIANT_ID,
                        PRODUCT_ID,
                        "SKU-001"))
                .satisfies(exception -> assertPostgresFailure(
                        exception,
                        "23503",
                        "fk_catalog_product_variants_product"));
    }

    @Test
    void rejectsVariantOwnedByProductFromDifferentTenant() {

        insertProduct(
                TENANT_A,
                PRODUCT_ID,
                "Monitor",
                "monitor",
                "DRAFT");

        assertThatThrownBy(() ->
                insertVariant(
                        TENANT_B,
                        VARIANT_ID,
                        PRODUCT_ID,
                        "SKU-001"))
                .satisfies(exception -> assertPostgresFailure(
                        exception,
                        "23503",
                        "fk_catalog_product_variants_product"));
    }

    @Test
    void rejectsEmptySkuWhenDomainIsBypassed() {

        insertProduct(
                TENANT_A,
                PRODUCT_ID,
                "Monitor",
                "monitor",
                "DRAFT");

        assertThatThrownBy(() ->
                insertVariant(
                        TENANT_A,
                        VARIANT_ID,
                        PRODUCT_ID,
                        ""))
                .satisfies(exception -> assertPostgresFailure(
                        exception,
                        "23514",
                        "ck_catalog_product_variants_sku_length"));
    }

    @Test
    void rejectsSkuAboveDatabaseLengthBoundary() {

        insertProduct(
                TENANT_A,
                PRODUCT_ID,
                "Monitor",
                "monitor",
                "DRAFT");

        assertThatThrownBy(() ->
                insertVariant(
                        TENANT_A,
                        VARIANT_ID,
                        PRODUCT_ID,
                        "A".repeat(65)))
                .satisfies(exception -> assertPostgresFailure(
                        exception,
                        "22001",
                        null));
    }

    // -------------------------------------------------------------------------
    // Category hierarchy
    // -------------------------------------------------------------------------

    @Test
    void allowsSameCategoryIdAcrossDifferentTenants() {

        insertCategory(
                TENANT_A,
                CATEGORY_ID,
                null,
                "Electronics",
                "electronics");

        insertCategory(
                TENANT_B,
                CATEGORY_ID,
                null,
                "Electronics",
                "electronics");
    }

    @Test
    void rejectsDuplicateCategorySlugWithinTenant() {

        insertCategory(
                TENANT_A,
                CATEGORY_ID,
                null,
                "Electronics",
                "electronics");

        assertThatThrownBy(() ->
                insertCategory(
                        TENANT_A,
                        UUID.randomUUID(),
                        null,
                        "Other Electronics",
                        "electronics"))
                .satisfies(exception -> assertPostgresFailure(
                        exception,
                        "23505",
                        "uq_catalog_categories_tenant_slug"));
    }

    @Test
    void allowsSameCategorySlugAcrossDifferentTenants() {

        insertCategory(
                TENANT_A,
                UUID.randomUUID(),
                null,
                "Electronics",
                "electronics");

        insertCategory(
                TENANT_B,
                UUID.randomUUID(),
                null,
                "Electronics",
                "electronics");
    }

    @Test
    void rejectsCategoryWhoseParentDoesNotExist() {

        assertThatThrownBy(() ->
                insertCategory(
                        TENANT_A,
                        CHILD_CATEGORY_ID,
                        CATEGORY_ID,
                        "Smartphones",
                        "smartphones"))
                .satisfies(exception -> assertPostgresFailure(
                        exception,
                        "23503",
                        "fk_catalog_categories_parent"));
    }

    @Test
    void rejectsCategoryWhoseParentBelongsToDifferentTenant() {

        insertCategory(
                TENANT_A,
                CATEGORY_ID,
                null,
                "Electronics",
                "electronics");

        assertThatThrownBy(() ->
                insertCategory(
                        TENANT_B,
                        CHILD_CATEGORY_ID,
                        CATEGORY_ID,
                        "Smartphones",
                        "smartphones"))
                .satisfies(exception -> assertPostgresFailure(
                        exception,
                        "23503",
                        "fk_catalog_categories_parent"));
    }

    @Test
    void rejectsSelfParentingCategoryAtDatabaseBoundary() {

        assertThatThrownBy(() ->
                insertCategory(
                        TENANT_A,
                        CATEGORY_ID,
                        CATEGORY_ID,
                        "Electronics",
                        "electronics"))
                .satisfies(exception -> assertPostgresFailure(
                        exception,
                        "23514",
                        "ck_catalog_categories_not_self_parent"));
    }

    @Test
    void rejectsInvalidCategorySlugWhenDomainIsBypassed() {

        assertThatThrownBy(() ->
                insertCategory(
                        TENANT_A,
                        CATEGORY_ID,
                        null,
                        "Electronics",
                        "electronics/store"))
                .satisfies(exception -> assertPostgresFailure(
                        exception,
                        "23514",
                        "ck_catalog_categories_slug"));
    }

    @Test
    void rejectsEmptyCategoryName() {

        assertThatThrownBy(() ->
                insertCategory(
                        TENANT_A,
                        CATEGORY_ID,
                        null,
                        "",
                        "electronics"))
                .satisfies(exception -> assertPostgresFailure(
                        exception,
                        "23514",
                        "ck_catalog_categories_name"));
    }

    // -------------------------------------------------------------------------
    // Product ↔ Category
    // -------------------------------------------------------------------------

    @Test
    void linksProductToCategoryWithinSameTenant() {

        insertProduct(
                TENANT_A,
                PRODUCT_ID,
                "Monitor",
                "monitor",
                "DRAFT");

        insertCategory(
                TENANT_A,
                CATEGORY_ID,
                null,
                "Electronics",
                "electronics");

        insertProductCategory(
                TENANT_A,
                PRODUCT_ID,
                CATEGORY_ID);

        var count =
                jdbcTemplate.queryForObject("""
                        SELECT COUNT(*)
                        FROM catalog.product_categories
                        WHERE tenant_id = ?
                          AND product_id = ?
                          AND category_id = ?
                        """,
                        Integer.class,
                        TENANT_A,
                        PRODUCT_ID,
                        CATEGORY_ID);

        assertThat(count)
                .isEqualTo(1);
    }

    @Test
    void rejectsDuplicateProductCategoryAssignment() {

        insertProduct(
                TENANT_A,
                PRODUCT_ID,
                "Monitor",
                "monitor",
                "DRAFT");

        insertCategory(
                TENANT_A,
                CATEGORY_ID,
                null,
                "Electronics",
                "electronics");

        insertProductCategory(
                TENANT_A,
                PRODUCT_ID,
                CATEGORY_ID);

        assertThatThrownBy(() ->
                insertProductCategory(
                        TENANT_A,
                        PRODUCT_ID,
                        CATEGORY_ID))
                .satisfies(exception -> assertPostgresFailure(
                        exception,
                        "23505",
                        "pk_catalog_product_categories"));
    }

    @Test
    void rejectsProductCategoryAssignmentWithoutProduct() {

        insertCategory(
                TENANT_A,
                CATEGORY_ID,
                null,
                "Electronics",
                "electronics");

        assertThatThrownBy(() ->
                insertProductCategory(
                        TENANT_A,
                        PRODUCT_ID,
                        CATEGORY_ID))
                .satisfies(exception -> assertPostgresFailure(
                        exception,
                        "23503",
                        "fk_catalog_product_categories_product"));
    }

    @Test
    void rejectsProductCategoryAssignmentWithoutCategory() {

        insertProduct(
                TENANT_A,
                PRODUCT_ID,
                "Monitor",
                "monitor",
                "DRAFT");

        assertThatThrownBy(() ->
                insertProductCategory(
                        TENANT_A,
                        PRODUCT_ID,
                        CATEGORY_ID))
                .satisfies(exception -> assertPostgresFailure(
                        exception,
                        "23503",
                        "fk_catalog_product_categories_category"));
    }

    @Test
    void rejectsCrossTenantProductCategoryAssignment() {

        insertProduct(
                TENANT_A,
                PRODUCT_ID,
                "Monitor",
                "monitor",
                "DRAFT");

        insertCategory(
                TENANT_B,
                CATEGORY_ID,
                null,
                "Electronics",
                "electronics");

        assertThatThrownBy(() ->
                insertProductCategory(
                        TENANT_A,
                        PRODUCT_ID,
                        CATEGORY_ID))
                .satisfies(exception -> assertPostgresFailure(
                        exception,
                        "23503",
                        "fk_catalog_product_categories_category"));
    }

    // -------------------------------------------------------------------------
    // Domain ↔ PostgreSQL parity
    // -------------------------------------------------------------------------

    @Test
    void rejectsNonCanonicalProductNameWhenDomainIsBypassed() {

        var invalidNames = new String[] {
                "\u00A0\u2007",
                "\u00A0Professional Monitor\u2007"
        };

        for (var name : invalidNames) {

            assertThatThrownBy(() ->
                    insertProduct(
                            TENANT_A,
                            UUID.randomUUID(),
                            name,
                            "monitor-" + UUID.randomUUID(),
                            "DRAFT"))
                    .satisfies(exception -> assertPostgresFailure(
                            exception,
                            "23514",
                            "ck_catalog_products_name"));
        }
    }

    @Test
    void rejectsNonCanonicalCategoryNameWhenDomainIsBypassed() {

        var invalidNames = new String[] {
                "\u00A0\u2007",
                "\u2007Electronics\u00A0"
        };

        for (var name : invalidNames) {

            assertThatThrownBy(() ->
                    insertCategory(
                            TENANT_A,
                            UUID.randomUUID(),
                            null,
                            name,
                            "category-" + UUID.randomUUID()))
                    .satisfies(exception -> assertPostgresFailure(
                            exception,
                            "23514",
                            "ck_catalog_categories_name"));
        }
    }

    @Test
    void rejectsSkuWithSurroundingUnicodeWhitespaceWhenDomainIsBypassed() {

        insertProduct(
                TENANT_A,
                PRODUCT_ID,
                "Monitor",
                "monitor",
                "DRAFT");

        var invalidSkus = new String[] {
                "\u00A0\u2007",
                "\u2007SKU-001\u00A0"
        };

        for (var sku : invalidSkus) {

            assertThatThrownBy(() ->
                    insertVariant(
                            TENANT_A,
                            UUID.randomUUID(),
                            PRODUCT_ID,
                            sku))
                    .satisfies(exception -> assertPostgresFailure(
                            exception,
                            "23514",
                            "ck_catalog_product_variants_sku_whitespace"));
        }
    }

    @Test
    void rejectsCompleteIsoControlRangeClassesInsideSku() {

        insertProduct(
                TENANT_A,
                PRODUCT_ID,
                "Monitor",
                "monitor",
                "DRAFT");

        /*
         * Build control characters at runtime.
         *
         * Java Unicode escapes such as \u000A are translated before lexical
         * analysis and therefore cannot safely represent LF inside a source
         * string literal.
         */
        var invalidSkus = new String[] {
                "SKU-" + Character.toString(10) + "-001",
                "SKU-" + Character.toString(127) + "-001",
                "SKU-" + Character.toString(133) + "-001",
                "SKU-" + Character.toString(159) + "-001"
        };

        for (var sku : invalidSkus) {

            assertThatThrownBy(() ->
                    insertVariant(
                            TENANT_A,
                            UUID.randomUUID(),
                            PRODUCT_ID,
                            sku))
                    .satisfies(exception -> assertPostgresFailure(
                            exception,
                            "23514",
                            "ck_catalog_product_variants_sku_control_characters"));
        }
    }
    @Test
    void allowsSkuAtMaximumUnicodeCharacterLength() {

        insertProduct(
                TENANT_A,
                PRODUCT_ID,
                "Monitor",
                "monitor",
                "DRAFT");

        var supplementaryLetter =
                new String(
                        Character.toChars(0x10400));

        var sku =
                supplementaryLetter.repeat(64);

        insertVariant(
                TENANT_A,
                VARIANT_ID,
                PRODUCT_ID,
                sku);

        var persistedLength =
                jdbcTemplate.queryForObject("""
                        SELECT char_length(sku)
                        FROM catalog.product_variants
                        WHERE tenant_id = ?
                          AND id = ?
                        """,
                        Integer.class,
                        TENANT_A,
                        VARIANT_ID);

        assertThat(persistedLength)
                .isEqualTo(64);
    }

    @Test
    void treatsProductSlugAsCaseSensitiveWithinTenant() {

        insertProduct(
                TENANT_A,
                UUID.randomUUID(),
                "Monitor Lower",
                "professional-monitor",
                "DRAFT");

        insertProduct(
                TENANT_A,
                UUID.randomUUID(),
                "Monitor Upper",
                "Professional-Monitor",
                "DRAFT");
    }

    @Test
    void treatsCategorySlugAsCaseSensitiveWithinTenant() {

        insertCategory(
                TENANT_A,
                UUID.randomUUID(),
                null,
                "Electronics Lower",
                "electronics");

        insertCategory(
                TENANT_A,
                UUID.randomUUID(),
                null,
                "Electronics Upper",
                "Electronics");
    }

    @Test
    void treatsSkuAsCaseSensitiveWithinTenant() {

        insertProduct(
                TENANT_A,
                PRODUCT_ID,
                "Monitor",
                "monitor",
                "DRAFT");

        insertVariant(
                TENANT_A,
                UUID.randomUUID(),
                PRODUCT_ID,
                "SKU-001");

        insertVariant(
                TENANT_A,
                UUID.randomUUID(),
                PRODUCT_ID,
                "sku-001");
    }
    // -------------------------------------------------------------------------
    // PostgreSQL failure evidence
    // -------------------------------------------------------------------------

    /**
     * Verifies that a relational scenario failed for the exact PostgreSQL reason
     * expected by the test rather than because an unrelated constraint happened
     * to reject the same statement first.
     *
     * @param exception Spring persistence exception raised by JdbcTemplate
     * @param expectedSqlState PostgreSQL SQLSTATE expected for the violation
     * @param expectedConstraint exact violated constraint, or null when the
     *        failure is produced by the PostgreSQL type boundary itself
     */
    private void assertPostgresFailure(
            Throwable exception,
            String expectedSqlState,
            String expectedConstraint) {

        assertThat(exception)
                .isInstanceOf(DataIntegrityViolationException.class);

        var rootCause =
                exception;

        while (rootCause.getCause() != null) {
            rootCause =
                    rootCause.getCause();
        }

        assertThat(rootCause)
                .isInstanceOf(PSQLException.class);

        var postgresException =
                (PSQLException) rootCause;

        assertThat(postgresException.getSQLState())
                .isEqualTo(expectedSqlState);

        var serverErrorMessage =
                postgresException.getServerErrorMessage();

        assertThat(serverErrorMessage)
                .isNotNull();

        if (expectedConstraint == null) {
            assertThat(serverErrorMessage.getConstraint())
                    .isNull();

            return;
        }

        assertThat(serverErrorMessage.getConstraint())
                .isEqualTo(expectedConstraint);
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void insertProduct(
            UUID tenantId,
            UUID productId,
            String name,
            String slug,
            String status) {

        jdbcTemplate.update("""
                INSERT INTO catalog.products (
                    tenant_id,
                    id,
                    name,
                    slug,
                    description,
                    status
                )
                VALUES (?, ?, ?, ?, NULL, ?)
                """,
                tenantId,
                productId,
                name,
                slug,
                status);
    }

    private void insertVariant(
            UUID tenantId,
            UUID variantId,
            UUID productId,
            String sku) {

        jdbcTemplate.update("""
                INSERT INTO catalog.product_variants (
                    tenant_id,
                    id,
                    product_id,
                    sku
                )
                VALUES (?, ?, ?, ?)
                """,
                tenantId,
                variantId,
                productId,
                sku);
    }

    private void insertCategory(
            UUID tenantId,
            UUID categoryId,
            UUID parentCategoryId,
            String name,
            String slug) {

        jdbcTemplate.update("""
                INSERT INTO catalog.categories (
                    tenant_id,
                    id,
                    parent_category_id,
                    name,
                    slug,
                    description
                )
                VALUES (?, ?, ?, ?, ?, NULL)
                """,
                tenantId,
                categoryId,
                parentCategoryId,
                name,
                slug);
    }

    private void insertProductCategory(
            UUID tenantId,
            UUID productId,
            UUID categoryId) {

        jdbcTemplate.update("""
                INSERT INTO catalog.product_categories (
                    tenant_id,
                    product_id,
                    category_id
                )
                VALUES (?, ?, ?)
                """,
                tenantId,
                productId,
                categoryId);
    }
}
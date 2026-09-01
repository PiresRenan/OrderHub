package io.github.piresrenan.orderhub.catalog.adapter.out.persistence.postgresql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.CannotCreateTransactionException;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.support.TransactionCallback;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import io.github.piresrenan.orderhub.catalog.application.port.out.CatalogPersistenceException;
import io.github.piresrenan.orderhub.catalog.application.port.out.CategoryRepository;
import io.github.piresrenan.orderhub.catalog.application.port.out.ProductRepository;
import io.github.piresrenan.orderhub.catalog.application.port.out.ProductVariantRepository;
import io.github.piresrenan.orderhub.catalog.domain.model.Category;
import io.github.piresrenan.orderhub.catalog.domain.model.Product;
import io.github.piresrenan.orderhub.catalog.domain.model.ProductStatus;
import io.github.piresrenan.orderhub.catalog.domain.model.ProductVariant;

/**
 * Verifies PostgreSQL mapping, tenant isolation, rehydration and aggregate-write
 * behavior for Catalog repositories.
 *
 * <p>
 * Schema constraints are tested separately by
 * {@link PostgreSqlCatalogSchemaConstraintsTest}. These tests instead verify the
 * application output ports and their PostgreSQL adapter behavior.
 * </p>
 */
@Testcontainers
class PostgreSqlCatalogRepositoriesTest {

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

    private static final UUID CATEGORY_A =
            UUID.fromString(
                    "55555555-5555-5555-5555-555555555555");

    private static final UUID CATEGORY_B =
            UUID.fromString(
                    "66666666-6666-6666-6666-666666666666");

    private static final UUID MISSING_CATEGORY =
            UUID.fromString(
                    "77777777-7777-7777-7777-777777777777");

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(POSTGRES_IMAGE)
                    .withDatabaseName("orderhub_test")
                    .withUsername("orderhub_test")
                    .withPassword("synthetic-test-password");

    private static JdbcTemplate jdbcTemplate;
    private static TransactionOperations transactionOperations;

    private ProductRepository productRepository;
    private ProductVariantRepository productVariantRepository;
    private CategoryRepository categoryRepository;

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

        transactionOperations =
                new TransactionTemplate(
                        new DataSourceTransactionManager(
                                dataSource));
    }

    @BeforeEach
    void resetRepositoriesAndBusinessData() {

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

        productRepository =
                new PostgreSqlProductRepository(
                        jdbcTemplate,
                        transactionOperations);

        productVariantRepository =
                new PostgreSqlProductVariantRepository(
                        jdbcTemplate,
                        transactionOperations);

        categoryRepository =
                new PostgreSqlCategoryRepository(
                        jdbcTemplate);
    }

    // -------------------------------------------------------------------------
    // Product repository
    // -------------------------------------------------------------------------

    @Test
    void savesAndRehydratesProductWithCategoryAssignments() {

        insertCategoryRow(
                TENANT_A,
                CATEGORY_A,
                null,
                "Electronics",
                "electronics",
                "Electronic devices.");

        insertCategoryRow(
                TENANT_A,
                CATEGORY_B,
                null,
                "Professional",
                "professional",
                null);

        var product =
                Product.create(
                        PRODUCT_ID,
                        TENANT_A,
                        "Professional Monitor",
                        "professional-monitor",
                        "Line one.\nLine two.",
                        List.of(
                                CATEGORY_A,
                                CATEGORY_B));

        var saved =
                productRepository.save(product);

        assertThat(saved)
                .isSameAs(product);

        var rehydrated =
                productRepository.findById(
                        TENANT_A,
                        PRODUCT_ID);

        assertThat(rehydrated)
                .isPresent();

        var persisted =
                rehydrated.orElseThrow();

        assertThat(persisted.id())
                .isEqualTo(PRODUCT_ID);

        assertThat(persisted.tenantId())
                .isEqualTo(TENANT_A);

        assertThat(persisted.name())
                .isEqualTo("Professional Monitor");

        assertThat(persisted.slug())
                .isEqualTo("professional-monitor");

        assertThat(persisted.description())
                .isEqualTo("Line one.\nLine two.");

        assertThat(persisted.categoryIds())
                .containsExactlyInAnyOrder(
                        CATEGORY_A,
                        CATEGORY_B);

        assertThat(persisted.status())
                .isEqualTo(ProductStatus.DRAFT);
    }

    @Test
    void rehydratesPersistedProductLifecycleState() {

        insertProductRow(
                TENANT_A,
                PRODUCT_ID,
                "Historical Monitor",
                "historical-monitor",
                "Historical product.",
                "ARCHIVED");

        insertCategoryRow(
                TENANT_A,
                CATEGORY_A,
                null,
                "Archive",
                "archive",
                null);

        insertProductCategoryRow(
                TENANT_A,
                PRODUCT_ID,
                CATEGORY_A);

        var rehydrated =
                productRepository.findById(
                        TENANT_A,
                        PRODUCT_ID);

        assertThat(rehydrated)
                .isPresent();

        var persisted =
                rehydrated.orElseThrow();

        assertThat(persisted.status())
                .isEqualTo(ProductStatus.ARCHIVED);

        assertThat(persisted.categoryIds())
                .containsExactly(CATEGORY_A);
    }

    @Test
    void returnsEmptyWhenProductDoesNotExistInsideTenant() {

        assertThat(productRepository.findById(
                TENANT_A,
                PRODUCT_ID))
                .isEmpty();
    }

    @Test
    void doesNotReadProductOwnedByAnotherTenant() {

        insertProductRow(
                TENANT_A,
                PRODUCT_ID,
                "Tenant A Monitor",
                "tenant-a-monitor",
                null,
                "DRAFT");

        assertThat(productRepository.findById(
                TENANT_B,
                PRODUCT_ID))
                .isEmpty();
    }

    @Test
    void rollsBackEntireProductAggregateWhenCategoryAssignmentFails() {

        insertCategoryRow(
                TENANT_A,
                CATEGORY_A,
                null,
                "Electronics",
                "electronics",
                null);

        var product =
                Product.create(
                        PRODUCT_ID,
                        TENANT_A,
                        "Professional Monitor",
                        "professional-monitor",
                        null,
                        List.of(
                                CATEGORY_A,
                                MISSING_CATEGORY));

        assertThatThrownBy(() ->
                productRepository.save(product))
                .satisfies(
                        this::assertCatalogPersistenceFailure);

        var productCount =
                jdbcTemplate.queryForObject("""
                        SELECT COUNT(*)
                        FROM catalog.products
                        WHERE tenant_id = ?
                          AND id = ?
                        """,
                        Integer.class,
                        TENANT_A,
                        PRODUCT_ID);

        var categoryAssignmentCount =
                jdbcTemplate.queryForObject("""
                        SELECT COUNT(*)
                        FROM catalog.product_categories
                        WHERE tenant_id = ?
                          AND product_id = ?
                        """,
                        Integer.class,
                        TENANT_A,
                        PRODUCT_ID);

        assertThat(productCount)
                .isZero();

        assertThat(categoryAssignmentCount)
                .isZero();
    }

    @Test
    void translatesProductConstraintFailureAtApplicationBoundary() {

        insertProductRow(
                TENANT_A,
                UUID.randomUUID(),
                "Existing Product",
                "duplicated-slug",
                null,
                "DRAFT");

        var duplicate =
                Product.create(
                        PRODUCT_ID,
                        TENANT_A,
                        "Different Product",
                        "duplicated-slug",
                        null,
                        List.of());

        assertThatThrownBy(() ->
                productRepository.save(duplicate))
                .satisfies(
                        this::assertCatalogPersistenceFailure);
    }

    // -------------------------------------------------------------------------
    // ProductVariant repository
    // -------------------------------------------------------------------------

    @Test
    void savesAndRehydratesProductVariantPreservingSkuExactly() {

        insertProductRow(
                TENANT_A,
                PRODUCT_ID,
                "Professional Monitor",
                "professional-monitor",
                null,
                "DRAFT");

        var variant =
                ProductVariant.create(
                        VARIANT_ID,
                        TENANT_A,
                        PRODUCT_ID,
                        "ERP/SKU:Rev.2 A");

        var saved =
                productVariantRepository.save(variant);

        assertThat(saved)
                .isSameAs(variant);

        var rehydrated =
                productVariantRepository.findById(
                        TENANT_A,
                        VARIANT_ID);

        assertThat(rehydrated)
                .isPresent();

        var persisted =
                rehydrated.orElseThrow();

        assertThat(persisted.id())
                .isEqualTo(VARIANT_ID);

        assertThat(persisted.tenantId())
                .isEqualTo(TENANT_A);

        assertThat(persisted.productId())
                .isEqualTo(PRODUCT_ID);

        assertThat(persisted.sku())
                .isEqualTo("ERP/SKU:Rev.2 A");
    }

    @Test
    void returnsEmptyWhenProductVariantDoesNotExistInsideTenant() {

        assertThat(productVariantRepository.findById(
                TENANT_A,
                VARIANT_ID))
                .isEmpty();
    }

    @Test
    void doesNotReadProductVariantOwnedByAnotherTenant() {

        insertProductRow(
                TENANT_A,
                PRODUCT_ID,
                "Professional Monitor",
                "professional-monitor",
                null,
                "DRAFT");

        insertVariantRow(
                TENANT_A,
                VARIANT_ID,
                PRODUCT_ID,
                "SKU-001");

        assertThat(productVariantRepository.findById(
                TENANT_B,
                VARIANT_ID))
                .isEmpty();
    }

    @Test
    void translatesProductVariantConstraintFailureAtApplicationBoundary() {

        insertProductRow(
                TENANT_A,
                PRODUCT_ID,
                "Professional Monitor",
                "professional-monitor",
                null,
                "DRAFT");

        insertVariantRow(
                TENANT_A,
                UUID.randomUUID(),
                PRODUCT_ID,
                "SKU-001");

        var duplicate =
                ProductVariant.create(
                        VARIANT_ID,
                        TENANT_A,
                        PRODUCT_ID,
                        "SKU-001");

        assertThatThrownBy(() ->
                productVariantRepository.save(duplicate))
                .satisfies(
                        this::assertCatalogPersistenceFailure);
    }

    // -------------------------------------------------------------------------
    // Category repository
    // -------------------------------------------------------------------------

    @Test
    void savesAndRehydratesRootCategory() {

        var category =
                Category.create(
                        CATEGORY_A,
                        TENANT_A,
                        null,
                        "Electronics",
                        "electronics",
                        "Electronic devices.");

        var saved =
                categoryRepository.save(category);

        assertThat(saved)
                .isSameAs(category);

        var rehydrated =
                categoryRepository.findById(
                        TENANT_A,
                        CATEGORY_A);

        assertThat(rehydrated)
                .isPresent();

        var persisted =
                rehydrated.orElseThrow();

        assertThat(persisted.id())
                .isEqualTo(CATEGORY_A);

        assertThat(persisted.tenantId())
                .isEqualTo(TENANT_A);

        assertThat(persisted.parentCategoryId())
                .isNull();

        assertThat(persisted.name())
                .isEqualTo("Electronics");

        assertThat(persisted.slug())
                .isEqualTo("electronics");

        assertThat(persisted.description())
                .isEqualTo("Electronic devices.");
    }

    @Test
    void savesAndRehydratesChildCategory() {

        insertCategoryRow(
                TENANT_A,
                CATEGORY_A,
                null,
                "Electronics",
                "electronics",
                null);

        var child =
                Category.create(
                        CATEGORY_B,
                        TENANT_A,
                        CATEGORY_A,
                        "Professional Displays",
                        "professional-displays",
                        null);

        categoryRepository.save(child);

        var rehydrated =
                categoryRepository.findById(
                        TENANT_A,
                        CATEGORY_B);

        assertThat(rehydrated)
                .isPresent();

        assertThat(rehydrated.orElseThrow().parentCategoryId())
                .isEqualTo(CATEGORY_A);
    }

    @Test
    void returnsEmptyWhenCategoryDoesNotExistInsideTenant() {

        assertThat(categoryRepository.findById(
                TENANT_A,
                CATEGORY_A))
                .isEmpty();
    }

    @Test
    void doesNotReadCategoryOwnedByAnotherTenant() {

        insertCategoryRow(
                TENANT_A,
                CATEGORY_A,
                null,
                "Electronics",
                "electronics",
                null);

        assertThat(categoryRepository.findById(
                TENANT_B,
                CATEGORY_A))
                .isEmpty();
    }

    @Test
    void translatesCategoryConstraintFailureAtApplicationBoundary() {

        var orphan =
                Category.create(
                        CATEGORY_B,
                        TENANT_A,
                        MISSING_CATEGORY,
                        "Orphan Category",
                        "orphan-category",
                        null);

        assertThatThrownBy(() ->
                categoryRepository.save(orphan))
                .satisfies(
                        this::assertCatalogPersistenceFailure);
    }

    // -------------------------------------------------------------------------
    // Product transaction infrastructure boundary
    // -------------------------------------------------------------------------

    @Test
    void translatesTransactionInitializationFailureAtApplicationBoundary() {

        var transactionFailure =
                new CannotCreateTransactionException(
                        "Synthetic transaction initialization failure.");

        var repository =
                new PostgreSqlProductRepository(
                        jdbcTemplate,
                        failingTransactionsWith(
                                transactionFailure));

        var product =
                Product.create(
                        PRODUCT_ID,
                        TENANT_A,
                        "Professional Monitor",
                        "professional-monitor",
                        null,
                        List.of());

        assertThatThrownBy(() ->
                repository.save(product))
                .satisfies(exception -> {

                    assertThat(exception)
                            .isInstanceOf(
                                    CatalogPersistenceException.class)
                            .hasMessage(
                                    "Catalog persistence operation failed.");

                    assertThat(exception.getCause())
                            .isSameAs(transactionFailure);
                });
    }

    @Test
    void translatesTransactionSystemFailureAtApplicationBoundary() {

        var transactionFailure =
                new TransactionSystemException(
                        "Synthetic transaction commit or rollback failure.");

        var repository =
                new PostgreSqlProductRepository(
                        jdbcTemplate,
                        failingTransactionsWith(
                                transactionFailure));

        var product =
                Product.create(
                        PRODUCT_ID,
                        TENANT_A,
                        "Professional Monitor",
                        "professional-monitor",
                        null,
                        List.of());

        assertThatThrownBy(() ->
                repository.save(product))
                .satisfies(exception -> {

                    assertThat(exception)
                            .isInstanceOf(
                                    CatalogPersistenceException.class)
                            .hasMessage(
                                    "Catalog persistence operation failed.");

                    assertThat(exception.getCause())
                            .isSameAs(transactionFailure);
                });
    }

    private TransactionOperations failingTransactionsWith(
            TransactionException transactionFailure) {

        return new TransactionOperations() {

            @Override
            public <T> T execute(
                    TransactionCallback<T> action) {

                throw transactionFailure;
            }
        };
    }
    // -------------------------------------------------------------------------
    // Assertions
    // -------------------------------------------------------------------------

    private void assertCatalogPersistenceFailure(
            Throwable exception) {

        assertThat(exception)
                .isInstanceOf(
                        CatalogPersistenceException.class)
                .hasMessage(
                        "Catalog persistence operation failed.")
                .hasCauseInstanceOf(
                        DataIntegrityViolationException.class);
    }

    // -------------------------------------------------------------------------
    // JDBC setup helpers
    // -------------------------------------------------------------------------

    private void insertProductRow(
            UUID tenantId,
            UUID productId,
            String name,
            String slug,
            String description,
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
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                tenantId,
                productId,
                name,
                slug,
                description,
                status);
    }

    private void insertVariantRow(
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

    private void insertCategoryRow(
            UUID tenantId,
            UUID categoryId,
            UUID parentCategoryId,
            String name,
            String slug,
            String description) {

        jdbcTemplate.update("""
                INSERT INTO catalog.categories (
                    tenant_id,
                    id,
                    parent_category_id,
                    name,
                    slug,
                    description
                )
                VALUES (?, ?, ?, ?, ?, ?)
                """,
                tenantId,
                categoryId,
                parentCategoryId,
                name,
                slug,
                description);
    }

    private void insertProductCategoryRow(
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
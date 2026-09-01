package io.github.piresrenan.orderhub.catalog.adapter.out.persistence.postgresql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.List;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.support.TransactionOperations;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import io.github.piresrenan.orderhub.catalog.application.port.out.CatalogMediaRepository;
import io.github.piresrenan.orderhub.catalog.application.port.out.CatalogPersistenceException;
import io.github.piresrenan.orderhub.catalog.application.port.out.ProductRepository;
import io.github.piresrenan.orderhub.catalog.application.port.out.ProductVariantRepository;
import io.github.piresrenan.orderhub.catalog.application.port.out.VariantBasePriceRepository;
import io.github.piresrenan.orderhub.catalog.domain.model.CatalogMedia;
import io.github.piresrenan.orderhub.catalog.domain.model.CatalogMediaType;
import io.github.piresrenan.orderhub.catalog.domain.model.Money;
import io.github.piresrenan.orderhub.catalog.domain.model.Product;
import io.github.piresrenan.orderhub.catalog.domain.model.ProductStatus;
import io.github.piresrenan.orderhub.catalog.domain.model.ProductVariant;
import io.github.piresrenan.orderhub.catalog.domain.model.ProductVariantAttribute;
import io.github.piresrenan.orderhub.catalog.domain.model.ProductVariantStatus;
import io.github.piresrenan.orderhub.catalog.domain.model.VariantBasePrice;

/**
 * PostgreSQL acceptance tests for the OH-011 Catalog commercial persistence model.
 */
@Testcontainers
class PostgreSqlCatalogCommercialRepositoriesTest {

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
    private static TransactionOperations transactionOperations;

    private ProductRepository productRepository;
    private ProductVariantRepository productVariantRepository;
    private VariantBasePriceRepository variantBasePriceRepository;
    private CatalogMediaRepository catalogMediaRepository;

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
    void resetBusinessDataAndRepositories() {

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

        variantBasePriceRepository =
                new PostgreSqlVariantBasePriceRepository(
                        jdbcTemplate);

        catalogMediaRepository =
                new PostgreSqlCatalogMediaRepository(
                        jdbcTemplate);
    }

    // -------------------------------------------------------------------------
    // Product commercial state
    // -------------------------------------------------------------------------

    @Test
    void savesRehydratesAndUpdatesProductBrandAndLifecycle() {

        var draft =
                Product.create(
                        PRODUCT_ID,
                        TENANT_A,
                        "Framework Laptop",
                        "framework-laptop",
                        "Modular notebook",
                        "Framework",
                        List.of());

        productRepository.save(draft);

        var firstRead =
                productRepository.findById(
                        TENANT_A,
                        PRODUCT_ID)
                        .orElseThrow();

        assertThat(firstRead.brand())
                .isEqualTo("Framework");

        assertThat(firstRead.status())
                .isEqualTo(ProductStatus.DRAFT);

        var activeVariant =
                ProductVariant.create(
                        VARIANT_ID,
                        TENANT_A,
                        PRODUCT_ID,
                        "FRAMEWORK-13")
                        .activate();

        var activeProduct =
                draft.activate(
                        List.of(activeVariant));

        productRepository.save(activeProduct);

        var secondRead =
                productRepository.findById(
                        TENANT_A,
                        PRODUCT_ID)
                        .orElseThrow();

        assertThat(secondRead.brand())
                .isEqualTo("Framework");

        assertThat(secondRead.status())
                .isEqualTo(ProductStatus.ACTIVE);

        assertThat(secondRead.name())
                .isEqualTo("Framework Laptop");
    }

    @Test
    void productCommercialStateRemainsTenantScoped() {

        var tenantAProduct =
                Product.create(
                        PRODUCT_ID,
                        TENANT_A,
                        "Tenant A Product",
                        "tenant-a-product",
                        null,
                        "Brand A",
                        List.of());

        var tenantBProduct =
                Product.create(
                        PRODUCT_ID,
                        TENANT_B,
                        "Tenant B Product",
                        "tenant-b-product",
                        null,
                        "Brand B",
                        List.of());

        productRepository.save(tenantAProduct);
        productRepository.save(tenantBProduct);

        assertThat(productRepository.findById(TENANT_A, PRODUCT_ID))
                .get()
                .extracting(Product::brand)
                .isEqualTo("Brand A");

        assertThat(productRepository.findById(TENANT_B, PRODUCT_ID))
                .get()
                .extracting(Product::brand)
                .isEqualTo("Brand B");
    }

    // -------------------------------------------------------------------------
    // ProductVariant aggregate persistence
    // -------------------------------------------------------------------------

    @Test
    void savesAndRehydratesCompleteProductVariantAggregate() {

        saveProduct(TENANT_A, PRODUCT_ID, "product-a");

        var variant =
                ProductVariant.create(
                        VARIANT_ID,
                        TENANT_A,
                        PRODUCT_ID,
                        "SKU-001",
                        "Notebook / Black",
                        "4006381333931",
                        "Part.No/A-01",
                        List.of(
                                ProductVariantAttribute.of(
                                        "color",
                                        "black"),
                                ProductVariantAttribute.of(
                                        "screen.size",
                                        "15.6")))
                        .activate();

        productVariantRepository.save(variant);

        var persisted =
                productVariantRepository.findById(
                        TENANT_A,
                        VARIANT_ID)
                        .orElseThrow();

        assertThat(persisted.id())
                .isEqualTo(VARIANT_ID);

        assertThat(persisted.tenantId())
                .isEqualTo(TENANT_A);

        assertThat(persisted.productId())
                .isEqualTo(PRODUCT_ID);

        assertThat(persisted.sku())
                .isEqualTo("SKU-001");

        assertThat(persisted.displayName())
                .isEqualTo("Notebook / Black");

        assertThat(persisted.gtin())
                .isEqualTo("4006381333931");

        assertThat(persisted.mpn())
                .isEqualTo("Part.No/A-01");

        assertThat(persisted.status())
                .isEqualTo(ProductVariantStatus.ACTIVE);

        assertThat(persisted.attributes())
                .containsExactly(
                        ProductVariantAttribute.of(
                                "color",
                                "black"),
                        ProductVariantAttribute.of(
                                "screen.size",
                                "15.6"));
    }

    @Test
    void updatesVariantLifecycleAndReplacesAttributeSnapshot() {

        saveProduct(TENANT_A, PRODUCT_ID, "product-a");

        var draft =
                ProductVariant.create(
                        VARIANT_ID,
                        TENANT_A,
                        PRODUCT_ID,
                        "SKU-001",
                        "Initial name",
                        null,
                        "MPN-001",
                        List.of(
                                ProductVariantAttribute.of(
                                        "color",
                                        "black"),
                                ProductVariantAttribute.of(
                                        "size",
                                        "small")));

        productVariantRepository.save(draft);

        var evolved =
                ProductVariant.rehydrate(
                        VARIANT_ID,
                        TENANT_A,
                        PRODUCT_ID,
                        "SKU-001",
                        "Updated name",
                        "4006381333931",
                        "MPN-001",
                        List.of(
                                ProductVariantAttribute.of(
                                        "color",
                                        "white"),
                                ProductVariantAttribute.of(
                                        "memory",
                                        "32GB")),
                        ProductVariantStatus.ACTIVE);

        productVariantRepository.save(evolved);

        var persisted =
                productVariantRepository.findById(
                        TENANT_A,
                        VARIANT_ID)
                        .orElseThrow();

        assertThat(persisted.status())
                .isEqualTo(ProductVariantStatus.ACTIVE);

        assertThat(persisted.displayName())
                .isEqualTo("Updated name");

        assertThat(persisted.gtin())
                .isEqualTo("4006381333931");

        assertThat(persisted.attributes())
                .containsExactly(
                        ProductVariantAttribute.of(
                                "color",
                                "white"),
                        ProductVariantAttribute.of(
                                "memory",
                                "32GB"));

        assertThat(persisted.attributes())
                .doesNotContain(
                        ProductVariantAttribute.of(
                                "size",
                                "small"));
    }

    @Test
    void rollsBackVariantRootWhenAttributePersistenceFails() {

        saveProduct(TENANT_A, PRODUCT_ID, "product-a");

        jdbcTemplate.execute("""
                ALTER TABLE catalog.product_variant_attributes
                ADD CONSTRAINT ck_test_force_variant_attribute_failure
                CHECK (attribute_key <> 'force_failure')
                """);

        try {
            var variant =
                    ProductVariant.create(
                            VARIANT_ID,
                            TENANT_A,
                            PRODUCT_ID,
                            "SKU-FAIL",
                            null,
                            null,
                            null,
                            List.of(
                                    ProductVariantAttribute.of(
                                            "force_failure",
                                            "synthetic")));

            assertThatThrownBy(() ->
                    productVariantRepository.save(variant))
                    .isInstanceOf(
                            CatalogPersistenceException.class)
                    .hasMessage(
                            "Catalog persistence operation failed.");

            var variantCount =
                    jdbcTemplate.queryForObject("""
                            SELECT COUNT(*)
                            FROM catalog.product_variants
                            WHERE tenant_id = ?
                              AND id = ?
                            """,
                            Integer.class,
                            TENANT_A,
                            VARIANT_ID);

            var attributeCount =
                    jdbcTemplate.queryForObject("""
                            SELECT COUNT(*)
                            FROM catalog.product_variant_attributes
                            WHERE tenant_id = ?
                              AND variant_id = ?
                            """,
                            Integer.class,
                            TENANT_A,
                            VARIANT_ID);

            assertThat(variantCount)
                    .isZero();

            assertThat(attributeCount)
                    .isZero();
        } finally {
            jdbcTemplate.execute("""
                    ALTER TABLE catalog.product_variant_attributes
                    DROP CONSTRAINT IF EXISTS ck_test_force_variant_attribute_failure
                    """);
        }
    }

    @Test
    void variantAggregateRemainsTenantScopedWithSameVariantIdentity() {

        saveProduct(TENANT_A, PRODUCT_ID, "product-a");
        saveProduct(TENANT_B, PRODUCT_ID, "product-b");

        var tenantA =
                ProductVariant.create(
                        VARIANT_ID,
                        TENANT_A,
                        PRODUCT_ID,
                        "SKU-A",
                        null,
                        null,
                        "MPN-A",
                        List.of(
                                ProductVariantAttribute.of(
                                        "tenant",
                                        "A")));

        var tenantB =
                ProductVariant.create(
                        VARIANT_ID,
                        TENANT_B,
                        PRODUCT_ID,
                        "SKU-B",
                        null,
                        null,
                        "MPN-B",
                        List.of(
                                ProductVariantAttribute.of(
                                        "tenant",
                                        "B")));

        productVariantRepository.save(tenantA);
        productVariantRepository.save(tenantB);

        assertThat(productVariantRepository.findById(TENANT_A, VARIANT_ID))
                .get()
                .extracting(ProductVariant::mpn)
                .isEqualTo("MPN-A");

        assertThat(productVariantRepository.findById(TENANT_B, VARIANT_ID))
                .get()
                .extracting(ProductVariant::mpn)
                .isEqualTo("MPN-B");
    }

    // -------------------------------------------------------------------------
    // VariantBasePrice persistence
    // -------------------------------------------------------------------------

    @Test
    void savesFindsAndUpdatesExactVariantBasePrice() {

        saveProductAndVariant(
                TENANT_A,
                PRODUCT_ID,
                VARIANT_ID,
                "SKU-A");

        var initial =
                VariantBasePrice.create(
                        TENANT_A,
                        VARIANT_ID,
                        Money.of("BRL", 199_90L));

        variantBasePriceRepository.save(initial);

        var firstRead =
                variantBasePriceRepository.findByVariantAndCurrency(
                        TENANT_A,
                        VARIANT_ID,
                        "BRL")
                        .orElseThrow();

        assertThat(firstRead.currencyCode())
                .isEqualTo("BRL");

        assertThat(firstRead.minorUnits())
                .isEqualTo(199_90L);

        var updated =
                VariantBasePrice.create(
                        TENANT_A,
                        VARIANT_ID,
                        Money.of("BRL", 179_90L));

        variantBasePriceRepository.save(updated);

        var secondRead =
                variantBasePriceRepository.findByVariantAndCurrency(
                        TENANT_A,
                        VARIANT_ID,
                        "BRL")
                        .orElseThrow();

        assertThat(secondRead.minorUnits())
                .isEqualTo(179_90L);
    }

    @Test
    void supportsMultipleCurrenciesAndTenantIsolationForBasePrice() {

        saveProductAndVariant(
                TENANT_A,
                PRODUCT_ID,
                VARIANT_ID,
                "SKU-A");

        saveProductAndVariant(
                TENANT_B,
                PRODUCT_ID,
                VARIANT_ID,
                "SKU-B");

        variantBasePriceRepository.save(
                VariantBasePrice.create(
                        TENANT_A,
                        VARIANT_ID,
                        Money.of("BRL", 10_000)));

        variantBasePriceRepository.save(
                VariantBasePrice.create(
                        TENANT_A,
                        VARIANT_ID,
                        Money.of("USD", 2_000)));

        variantBasePriceRepository.save(
                VariantBasePrice.create(
                        TENANT_B,
                        VARIANT_ID,
                        Money.of("BRL", 30_000)));

        assertThat(
                variantBasePriceRepository
                        .findByVariantAndCurrency(
                                TENANT_A,
                                VARIANT_ID,
                                "BRL")
                        .orElseThrow()
                        .minorUnits())
                .isEqualTo(10_000);

        assertThat(
                variantBasePriceRepository
                        .findByVariantAndCurrency(
                                TENANT_A,
                                VARIANT_ID,
                                "USD")
                        .orElseThrow()
                        .minorUnits())
                .isEqualTo(2_000);

        assertThat(
                variantBasePriceRepository
                        .findByVariantAndCurrency(
                                TENANT_B,
                                VARIANT_ID,
                                "BRL")
                        .orElseThrow()
                        .minorUnits())
                .isEqualTo(30_000);

        assertThat(
                variantBasePriceRepository
                        .findByVariantAndCurrency(
                                TENANT_B,
                                VARIANT_ID,
                                "USD"))
                .isEmpty();
    }

    // -------------------------------------------------------------------------
    // CatalogMedia persistence
    // -------------------------------------------------------------------------

    @Test
    void savesAndOrdersProductMediaDeterministically() {

        saveProduct(TENANT_A, PRODUCT_ID, "product-a");

        var firstId =
                UUID.fromString(
                        "00000000-0000-0000-0000-000000000001");

        var secondId =
                UUID.fromString(
                        "00000000-0000-0000-0000-000000000002");

        var thirdId =
                UUID.fromString(
                        "00000000-0000-0000-0000-000000000003");

        catalogMediaRepository.save(
                CatalogMedia.forProduct(
                        thirdId,
                        TENANT_A,
                        PRODUCT_ID,
                        CatalogMediaType.IMAGE,
                        "image-3",
                        "Third",
                        2,
                        false));

        catalogMediaRepository.save(
                CatalogMedia.forProduct(
                        secondId,
                        TENANT_A,
                        PRODUCT_ID,
                        CatalogMediaType.IMAGE,
                        "image-2",
                        "Second",
                        1,
                        false));

        catalogMediaRepository.save(
                CatalogMedia.forProduct(
                        firstId,
                        TENANT_A,
                        PRODUCT_ID,
                        CatalogMediaType.IMAGE,
                        "image-1",
                        "First",
                        1,
                        true));

        var media =
                catalogMediaRepository.findByProduct(
                        TENANT_A,
                        PRODUCT_ID);

        assertThat(media)
                .extracting(CatalogMedia::id)
                .containsExactly(
                        firstId,
                        secondId,
                        thirdId);

        assertThat(media.get(0).primary())
                .isTrue();

        assertThat(media.get(0).altText())
                .isEqualTo("First");
    }

    @Test
    void savesAndOrdersVariantMediaWithoutLeakingAcrossOwnersOrTenants() {

        saveProductAndVariant(
                TENANT_A,
                PRODUCT_ID,
                VARIANT_ID,
                "SKU-A");

        saveProductAndVariant(
                TENANT_B,
                PRODUCT_ID,
                VARIANT_ID,
                "SKU-B");

        var tenantAVariantMedia =
                CatalogMedia.forVariant(
                        UUID.randomUUID(),
                        TENANT_A,
                        VARIANT_ID,
                        CatalogMediaType.VIDEO,
                        "tenant-a-video",
                        null,
                        0,
                        true);

        var tenantAProductMedia =
                CatalogMedia.forProduct(
                        UUID.randomUUID(),
                        TENANT_A,
                        PRODUCT_ID,
                        CatalogMediaType.IMAGE,
                        "tenant-a-product-image",
                        null,
                        0,
                        true);

        var tenantBVariantMedia =
                CatalogMedia.forVariant(
                        UUID.randomUUID(),
                        TENANT_B,
                        VARIANT_ID,
                        CatalogMediaType.VIDEO,
                        "tenant-b-video",
                        null,
                        0,
                        true);

        catalogMediaRepository.save(tenantAVariantMedia);
        catalogMediaRepository.save(tenantAProductMedia);
        catalogMediaRepository.save(tenantBVariantMedia);

        assertThat(
                catalogMediaRepository.findByVariant(
                        TENANT_A,
                        VARIANT_ID))
                .extracting(CatalogMedia::reference)
                .containsExactly("tenant-a-video");

        assertThat(
                catalogMediaRepository.findByProduct(
                        TENANT_A,
                        PRODUCT_ID))
                .extracting(CatalogMedia::reference)
                .containsExactly("tenant-a-product-image");

        assertThat(
                catalogMediaRepository.findByVariant(
                        TENANT_B,
                        VARIANT_ID))
                .extracting(CatalogMedia::reference)
                .containsExactly("tenant-b-video");
    }

    @Test
    void translatesDuplicatePrimaryMediaConstraintAtApplicationBoundary() {

        saveProduct(TENANT_A, PRODUCT_ID, "product-a");

        catalogMediaRepository.save(
                CatalogMedia.forProduct(
                        UUID.randomUUID(),
                        TENANT_A,
                        PRODUCT_ID,
                        CatalogMediaType.IMAGE,
                        "primary-1",
                        null,
                        0,
                        true));

        var duplicatePrimary =
                CatalogMedia.forProduct(
                        UUID.randomUUID(),
                        TENANT_A,
                        PRODUCT_ID,
                        CatalogMediaType.IMAGE,
                        "primary-2",
                        null,
                        1,
                        true);

        assertThatThrownBy(() ->
                catalogMediaRepository.save(
                        duplicatePrimary))
                .isInstanceOf(
                        CatalogPersistenceException.class)
                .hasMessage(
                        "Catalog persistence operation failed.");
    }

    // -------------------------------------------------------------------------
    // Helpers
    // -------------------------------------------------------------------------

    private void saveProduct(
            UUID tenantId,
            UUID productId,
            String slug) {

        productRepository.save(
                Product.create(
                        productId,
                        tenantId,
                        "Product " + slug,
                        slug,
                        null,
                        null,
                        List.of()));
    }

    private void saveProductAndVariant(
            UUID tenantId,
            UUID productId,
            UUID variantId,
            String sku) {

        saveProduct(
                tenantId,
                productId,
                "product-" + tenantId.toString().substring(0, 8));

        productVariantRepository.save(
                ProductVariant.create(
                        variantId,
                        tenantId,
                        productId,
                        sku));
    }
}
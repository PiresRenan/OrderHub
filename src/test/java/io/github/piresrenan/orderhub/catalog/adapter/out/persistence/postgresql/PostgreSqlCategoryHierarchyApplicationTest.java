package io.github.piresrenan.orderhub.catalog.adapter.out.persistence.postgresql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import io.github.piresrenan.orderhub.catalog.application.port.in.CategoryHierarchyViolationException;
import io.github.piresrenan.orderhub.catalog.application.port.in.SaveCategoryUseCase;
import io.github.piresrenan.orderhub.catalog.application.port.out.CategoryRepository;
import io.github.piresrenan.orderhub.catalog.application.service.SaveCategoryService;
import io.github.piresrenan.orderhub.catalog.domain.model.Category;

/**
 * PostgreSQL proof that Category hierarchy mutation is guarded by the
 * application boundary before persistence.
 */
@Testcontainers
class PostgreSqlCategoryHierarchyApplicationTest {

    private static final DockerImageName POSTGRES_IMAGE =
            DockerImageName.parse(
                    "postgres:18.6-trixie@sha256:"
                            + "4ef4dbc939d61acea57712655ddb4b4ab27419c913f94cca0cd57cb3ea3c2280")
                    .asCompatibleSubstituteFor("postgres");

    private static final UUID TENANT_ID =
            UUID.fromString(
                    "11111111-1111-1111-1111-111111111111");

    @Container
    private static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer(POSTGRES_IMAGE)
                    .withDatabaseName("orderhub_test")
                    .withUsername("orderhub_test")
                    .withPassword("synthetic-test-password");

    private static JdbcTemplate jdbcTemplate;

    private CategoryRepository repository;
    private SaveCategoryUseCase service;

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
    void resetCatalogAndApplicationBoundary() {

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

        repository =
                new PostgreSqlCategoryRepository(
                        jdbcTemplate);

        service =
                new SaveCategoryService(
                        repository);
    }

    @Test
    void safelyReparentsPersistedCategoryThroughApplicationBoundary() {

        var rootAId =
                UUID.randomUUID();

        var rootBId =
                UUID.randomUUID();

        var childId =
                UUID.randomUUID();

        repository.save(
                category(
                        rootAId,
                        null,
                        "root-a"));

        repository.save(
                category(
                        rootBId,
                        null,
                        "root-b"));

        repository.save(
                category(
                        childId,
                        rootAId,
                        "child"));

        var reparented =
                category(
                        childId,
                        rootBId,
                        "child");

        var saved =
                service.save(reparented);

        assertThat(saved.parentCategoryId())
                .isEqualTo(rootBId);

        var persisted =
                repository.findById(
                        TENANT_ID,
                        childId)
                        .orElseThrow();

        assertThat(persisted.parentCategoryId())
                .isEqualTo(rootBId);
    }

    @Test
    void rejectsArbitraryDepthCycleAndLeavesStoredTreeUnchanged() {

        var rootId =
                UUID.randomUUID();

        var levelOneId =
                UUID.randomUUID();

        var levelTwoId =
                UUID.randomUUID();

        var levelThreeId =
                UUID.randomUUID();

        repository.save(
                category(
                        rootId,
                        null,
                        "root"));

        repository.save(
                category(
                        levelOneId,
                        rootId,
                        "level-one"));

        repository.save(
                category(
                        levelTwoId,
                        levelOneId,
                        "level-two"));

        repository.save(
                category(
                        levelThreeId,
                        levelTwoId,
                        "level-three"));

        var invalidRoot =
                category(
                        rootId,
                        levelThreeId,
                        "root");

        assertThatThrownBy(() ->
                service.save(invalidRoot))
                .isInstanceOf(
                        CategoryHierarchyViolationException.class)
                .hasMessage(
                        "Category hierarchy is invalid.");

        var persistedRoot =
                repository.findById(
                        TENANT_ID,
                        rootId)
                        .orElseThrow();

        assertThat(persistedRoot.parentCategoryId())
                .isNull();

        var categoryCount =
                jdbcTemplate.queryForObject("""
                        SELECT COUNT(*)
                        FROM catalog.categories
                        WHERE tenant_id = ?
                        """,
                        Integer.class,
                        TENANT_ID);

        assertThat(categoryCount)
                .isEqualTo(4);
    }

    private static Category category(
            UUID id,
            UUID parentCategoryId,
            String slug) {

        return Category.create(
                id,
                TENANT_ID,
                parentCategoryId,
                "Category " + slug,
                slug,
                null);
    }
}
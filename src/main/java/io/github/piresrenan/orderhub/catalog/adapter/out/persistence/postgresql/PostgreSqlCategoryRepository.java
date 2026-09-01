package io.github.piresrenan.orderhub.catalog.adapter.out.persistence.postgresql;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.piresrenan.orderhub.catalog.application.port.out.CatalogPersistenceException;
import io.github.piresrenan.orderhub.catalog.application.port.out.CategoryRepository;
import io.github.piresrenan.orderhub.catalog.domain.model.Category;

/**
 * PostgreSQL adapter for tenant-owned Category hierarchy nodes.
 */
public final class PostgreSqlCategoryRepository
        implements CategoryRepository {

    private static final String INSERT_CATEGORY_SQL = """
            INSERT INTO catalog.categories (
                tenant_id,
                id,
                parent_category_id,
                name,
                slug,
                description
            )
            VALUES (?, ?, ?, ?, ?, ?)
            """;

    private static final String FIND_CATEGORY_SQL = """
            SELECT
                tenant_id,
                id,
                parent_category_id,
                name,
                slug,
                description
            FROM catalog.categories
            WHERE tenant_id = ?
              AND id = ?
            """;

    private final JdbcTemplate jdbcTemplate;

    public PostgreSqlCategoryRepository(
            JdbcTemplate jdbcTemplate) {

        this.jdbcTemplate =
                Objects.requireNonNull(
                        jdbcTemplate,
                        "jdbcTemplate");
    }

    @Override
    public Category save(
            Category category) {

        try {
            jdbcTemplate.update(
                    INSERT_CATEGORY_SQL,
                    category.tenantId(),
                    category.id(),
                    category.parentCategoryId(),
                    category.name(),
                    category.slug(),
                    category.description());

            return category;
        } catch (DataAccessException exception) {
            throw new CatalogPersistenceException(
                    exception);
        }
    }

    @Override
    public Optional<Category> findById(
            UUID tenantId,
            UUID categoryId) {

        try {
            var categories =
                    jdbcTemplate.query(
                            FIND_CATEGORY_SQL,
                            (resultSet, rowNumber) ->
                                    Category.create(
                                            resultSet.getObject(
                                                    "id",
                                                    UUID.class),
                                            resultSet.getObject(
                                                    "tenant_id",
                                                    UUID.class),
                                            resultSet.getObject(
                                                    "parent_category_id",
                                                    UUID.class),
                                            resultSet.getString(
                                                    "name"),
                                            resultSet.getString(
                                                    "slug"),
                                            resultSet.getString(
                                                    "description")),
                            tenantId,
                            categoryId);

            return categories.stream()
                    .findFirst();
        } catch (DataAccessException exception) {
            throw new CatalogPersistenceException(
                    exception);
        }
    }
}
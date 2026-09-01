package io.github.piresrenan.orderhub.catalog.adapter.out.persistence.postgresql;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionOperations;

import io.github.piresrenan.orderhub.catalog.application.port.out.CatalogPersistenceException;
import io.github.piresrenan.orderhub.catalog.application.port.out.ProductRepository;
import io.github.piresrenan.orderhub.catalog.domain.model.Product;
import io.github.piresrenan.orderhub.catalog.domain.model.ProductStatus;

/**
 * PostgreSQL adapter for complete Product persistence and rehydration.
 *
 * <p>
 * A Product persistence operation consists of the Product root row plus all of
 * its Category assignments. Those writes therefore execute inside one externally
 * configured transaction boundary.
 * </p>
 *
 * <p>
 * Reads use one joined SQL statement so Product state and Category assignments
 * are reconstructed from one PostgreSQL statement snapshot rather than from
 * multiple independent reads.
 * </p>
 */
public final class PostgreSqlProductRepository
        implements ProductRepository {

    private static final String INSERT_PRODUCT_SQL = """
            INSERT INTO catalog.products (
                tenant_id,
                id,
                name,
                slug,
                description,
                status
            )
            VALUES (?, ?, ?, ?, ?, ?)
            """;

    private static final String INSERT_PRODUCT_CATEGORY_SQL = """
            INSERT INTO catalog.product_categories (
                tenant_id,
                product_id,
                category_id
            )
            VALUES (?, ?, ?)
            """;

    private static final String FIND_PRODUCT_SQL = """
            SELECT
                p.tenant_id,
                p.id,
                p.name,
                p.slug,
                p.description,
                p.status,
                pc.category_id
            FROM catalog.products p
            LEFT JOIN catalog.product_categories pc
              ON pc.tenant_id = p.tenant_id
             AND pc.product_id = p.id
            WHERE p.tenant_id = ?
              AND p.id = ?
            ORDER BY pc.category_id
            """;

    private final JdbcTemplate jdbcTemplate;
    private final TransactionOperations transactionOperations;

    public PostgreSqlProductRepository(
            JdbcTemplate jdbcTemplate,
            TransactionOperations transactionOperations) {

        this.jdbcTemplate =
                Objects.requireNonNull(
                        jdbcTemplate,
                        "jdbcTemplate");

        this.transactionOperations =
                Objects.requireNonNull(
                        transactionOperations,
                        "transactionOperations");
    }

    /**
     * Persists Product root and Category assignments atomically.
     */
    @Override
    public Product save(
            Product product) {

        try {
            return transactionOperations.execute(
                    transactionStatus -> {

                        jdbcTemplate.update(
                                INSERT_PRODUCT_SQL,
                                product.tenantId(),
                                product.id(),
                                product.name(),
                                product.slug(),
                                product.description(),
                                product.status().name());

                        for (var categoryId : product.categoryIds()) {
                            jdbcTemplate.update(
                                    INSERT_PRODUCT_CATEGORY_SQL,
                                    product.tenantId(),
                                    product.id(),
                                    categoryId);
                        }

                        return product;
                    });
        } catch (DataAccessException | TransactionException exception) {
            throw new CatalogPersistenceException(
                    exception);
        }
    }

    /**
     * Reconstructs one Product strictly inside the supplied Tenant.
     */
    @Override
    public Optional<Product> findById(
            UUID tenantId,
            UUID productId) {

        try {
            var rows =
                    jdbcTemplate.query(
                            FIND_PRODUCT_SQL,
                            (resultSet, rowNumber) ->
                                    new ProductRow(
                                            resultSet.getObject(
                                                    "tenant_id",
                                                    UUID.class),
                                            resultSet.getObject(
                                                    "id",
                                                    UUID.class),
                                            resultSet.getString(
                                                    "name"),
                                            resultSet.getString(
                                                    "slug"),
                                            resultSet.getString(
                                                    "description"),
                                            ProductStatus.valueOf(
                                                    resultSet.getString(
                                                            "status")),
                                            resultSet.getObject(
                                                    "category_id",
                                                    UUID.class)),
                            tenantId,
                            productId);

            if (rows.isEmpty()) {
                return Optional.empty();
            }

            var root =
                    rows.get(0);

            var categoryIds =
                    rows.stream()
                            .map(ProductRow::categoryId)
                            .filter(Objects::nonNull)
                            .toList();

            return Optional.of(
                    Product.rehydrate(
                            root.id(),
                            root.tenantId(),
                            root.name(),
                            root.slug(),
                            root.description(),
                            categoryIds,
                            root.status()));
        } catch (DataAccessException exception) {
            throw new CatalogPersistenceException(
                    exception);
        }
    }

    private record ProductRow(
            UUID tenantId,
            UUID id,
            String name,
            String slug,
            String description,
            ProductStatus status,
            UUID categoryId) {
    }
}
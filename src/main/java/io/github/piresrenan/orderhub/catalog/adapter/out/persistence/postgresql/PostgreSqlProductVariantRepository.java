package io.github.piresrenan.orderhub.catalog.adapter.out.persistence.postgresql;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.piresrenan.orderhub.catalog.application.port.out.CatalogPersistenceException;
import io.github.piresrenan.orderhub.catalog.application.port.out.ProductVariantRepository;
import io.github.piresrenan.orderhub.catalog.domain.model.ProductVariant;

/**
 * PostgreSQL adapter for sellable ProductVariant persistence.
 */
public final class PostgreSqlProductVariantRepository
        implements ProductVariantRepository {

    private static final String INSERT_VARIANT_SQL = """
            INSERT INTO catalog.product_variants (
                tenant_id,
                id,
                product_id,
                sku
            )
            VALUES (?, ?, ?, ?)
            """;

    private static final String FIND_VARIANT_SQL = """
            SELECT
                tenant_id,
                id,
                product_id,
                sku
            FROM catalog.product_variants
            WHERE tenant_id = ?
              AND id = ?
            """;

    private final JdbcTemplate jdbcTemplate;

    public PostgreSqlProductVariantRepository(
            JdbcTemplate jdbcTemplate) {

        this.jdbcTemplate =
                Objects.requireNonNull(
                        jdbcTemplate,
                        "jdbcTemplate");
    }

    @Override
    public ProductVariant save(
            ProductVariant variant) {

        try {
            jdbcTemplate.update(
                    INSERT_VARIANT_SQL,
                    variant.tenantId(),
                    variant.id(),
                    variant.productId(),
                    variant.sku());

            return variant;
        } catch (DataAccessException exception) {
            throw new CatalogPersistenceException(
                    exception);
        }
    }

    @Override
    public Optional<ProductVariant> findById(
            UUID tenantId,
            UUID variantId) {

        try {
            var variants =
                    jdbcTemplate.query(
                            FIND_VARIANT_SQL,
                            (resultSet, rowNumber) ->
                                    ProductVariant.create(
                                            resultSet.getObject(
                                                    "id",
                                                    UUID.class),
                                            resultSet.getObject(
                                                    "tenant_id",
                                                    UUID.class),
                                            resultSet.getObject(
                                                    "product_id",
                                                    UUID.class),
                                            resultSet.getString(
                                                    "sku")),
                            tenantId,
                            variantId);

            return variants.stream()
                    .findFirst();
        } catch (DataAccessException exception) {
            throw new CatalogPersistenceException(
                    exception);
        }
    }
}
package io.github.piresrenan.orderhub.catalog.adapter.out.persistence.postgresql;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.piresrenan.orderhub.catalog.application.port.out.CatalogOrderabilityRepository;
import io.github.piresrenan.orderhub.catalog.application.port.out.CatalogPersistenceException;

/**
 * PostgreSQL locking reads for Catalog Order-placement eligibility.
 *
 * <p>
 * Transaction ownership remains with Orders. These locking reads therefore
 * participate in the already active create-Order physical transaction.
 * </p>
 */
public final class PostgreSqlCatalogOrderabilityRepository
        implements CatalogOrderabilityRepository {

    private static final String LOCK_ACTIVE_VARIANT_SQL = """
            SELECT product_id
            FROM catalog.product_variants
            WHERE tenant_id = ?
              AND id = ?
              AND status = 'ACTIVE'
            FOR SHARE
            """;

    private static final String LOCK_ACTIVE_PRODUCT_SQL = """
            SELECT id
            FROM catalog.products
            WHERE tenant_id = ?
              AND id = ?
              AND status = 'ACTIVE'
            FOR SHARE
            """;

    private final JdbcTemplate jdbcTemplate;

    public PostgreSqlCatalogOrderabilityRepository(
            JdbcTemplate jdbcTemplate) {

        this.jdbcTemplate =
                Objects.requireNonNull(
                        jdbcTemplate,
                        "jdbcTemplate");
    }

    @Override
    public Optional<UUID> lockActiveVariantProductId(
            UUID tenantId,
            UUID variantId) {

        Objects.requireNonNull(
                tenantId,
                "tenantId");

        Objects.requireNonNull(
                variantId,
                "variantId");

        try {
            var productIds =
                    jdbcTemplate.query(
                            LOCK_ACTIVE_VARIANT_SQL,
                            (resultSet, rowNumber) ->
                                    resultSet.getObject(
                                            "product_id",
                                            UUID.class),
                            tenantId,
                            variantId);

            return productIds.stream()
                    .findFirst();

        } catch (DataAccessException exception) {

            throw new CatalogPersistenceException(
                    exception);
        }
    }

    @Override
    public boolean lockActiveProduct(
            UUID tenantId,
            UUID productId) {

        Objects.requireNonNull(
                tenantId,
                "tenantId");

        Objects.requireNonNull(
                productId,
                "productId");

        try {
            var productIds =
                    jdbcTemplate.query(
                            LOCK_ACTIVE_PRODUCT_SQL,
                            (resultSet, rowNumber) ->
                                    resultSet.getObject(
                                            "id",
                                            UUID.class),
                            tenantId,
                            productId);

            return !productIds.isEmpty();

        } catch (DataAccessException exception) {

            throw new CatalogPersistenceException(
                    exception);
        }
    }
}

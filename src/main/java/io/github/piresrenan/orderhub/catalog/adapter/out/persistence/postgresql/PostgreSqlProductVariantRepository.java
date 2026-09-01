package io.github.piresrenan.orderhub.catalog.adapter.out.persistence.postgresql;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.TransactionException;
import org.springframework.transaction.support.TransactionOperations;

import io.github.piresrenan.orderhub.catalog.application.port.out.CatalogPersistenceException;
import io.github.piresrenan.orderhub.catalog.application.port.out.ProductVariantRepository;
import io.github.piresrenan.orderhub.catalog.domain.model.ProductVariant;
import io.github.piresrenan.orderhub.catalog.domain.model.ProductVariantAttribute;
import io.github.piresrenan.orderhub.catalog.domain.model.ProductVariantStatus;

/**
 * PostgreSQL adapter for complete ProductVariant aggregate persistence.
 */
public final class PostgreSqlProductVariantRepository
        implements ProductVariantRepository {

    private static final String UPSERT_VARIANT_SQL = """
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
            ON CONFLICT (tenant_id, id)
            DO UPDATE SET
                product_id = EXCLUDED.product_id,
                sku = EXCLUDED.sku,
                display_name = EXCLUDED.display_name,
                gtin = EXCLUDED.gtin,
                mpn = EXCLUDED.mpn,
                status = EXCLUDED.status
            """;

    private static final String DELETE_ATTRIBUTES_SQL = """
            DELETE FROM catalog.product_variant_attributes
            WHERE tenant_id = ?
              AND variant_id = ?
            """;

    private static final String INSERT_ATTRIBUTE_SQL = """
            INSERT INTO catalog.product_variant_attributes (
                tenant_id,
                variant_id,
                attribute_key,
                attribute_value
            )
            VALUES (?, ?, ?, ?)
            """;

    private static final String FIND_VARIANT_SQL = """
            SELECT
                v.tenant_id,
                v.id,
                v.product_id,
                v.sku,
                v.display_name,
                v.gtin,
                v.mpn,
                v.status,
                a.attribute_key,
                a.attribute_value
            FROM catalog.product_variants v
            LEFT JOIN catalog.product_variant_attributes a
              ON a.tenant_id = v.tenant_id
             AND a.variant_id = v.id
            WHERE v.tenant_id = ?
              AND v.id = ?
            ORDER BY a.attribute_key COLLATE "C"
            """;

    private final JdbcTemplate jdbcTemplate;
    private final TransactionOperations transactionOperations;

    public PostgreSqlProductVariantRepository(
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

    @Override
    public ProductVariant save(
            ProductVariant variant) {

        try {
            return transactionOperations.execute(
                    transactionStatus -> {

                        jdbcTemplate.update(
                                UPSERT_VARIANT_SQL,
                                variant.tenantId(),
                                variant.id(),
                                variant.productId(),
                                variant.sku(),
                                variant.displayName(),
                                variant.gtin(),
                                variant.mpn(),
                                variant.status().name());

                        jdbcTemplate.update(
                                DELETE_ATTRIBUTES_SQL,
                                variant.tenantId(),
                                variant.id());

                        for (var attribute : variant.attributes()) {
                            jdbcTemplate.update(
                                    INSERT_ATTRIBUTE_SQL,
                                    variant.tenantId(),
                                    variant.id(),
                                    attribute.key(),
                                    attribute.value());
                        }

                        return variant;
                    });
        } catch (DataAccessException | TransactionException exception) {
            throw new CatalogPersistenceException(
                    exception);
        }
    }

    @Override
    public Optional<ProductVariant> findById(
            UUID tenantId,
            UUID variantId) {

        try {
            var rows =
                    jdbcTemplate.query(
                            FIND_VARIANT_SQL,
                            (resultSet, rowNumber) ->
                                    new VariantRow(
                                            resultSet.getObject(
                                                    "tenant_id",
                                                    UUID.class),
                                            resultSet.getObject(
                                                    "id",
                                                    UUID.class),
                                            resultSet.getObject(
                                                    "product_id",
                                                    UUID.class),
                                            resultSet.getString(
                                                    "sku"),
                                            resultSet.getString(
                                                    "display_name"),
                                            resultSet.getString(
                                                    "gtin"),
                                            resultSet.getString(
                                                    "mpn"),
                                            ProductVariantStatus.valueOf(
                                                    resultSet.getString(
                                                            "status")),
                                            resultSet.getString(
                                                    "attribute_key"),
                                            resultSet.getString(
                                                    "attribute_value")),
                            tenantId,
                            variantId);

            if (rows.isEmpty()) {
                return Optional.empty();
            }

            var root =
                    rows.get(0);

            var attributes =
                    rows.stream()
                            .filter(row -> row.attributeKey() != null)
                            .map(row ->
                                    ProductVariantAttribute.of(
                                            row.attributeKey(),
                                            row.attributeValue()))
                            .toList();

            return Optional.of(
                    ProductVariant.rehydrate(
                            root.id(),
                            root.tenantId(),
                            root.productId(),
                            root.sku(),
                            root.displayName(),
                            root.gtin(),
                            root.mpn(),
                            attributes,
                            root.status()));
        } catch (DataAccessException exception) {
            throw new CatalogPersistenceException(
                    exception);
        }
    }

    private record VariantRow(
            UUID tenantId,
            UUID id,
            UUID productId,
            String sku,
            String displayName,
            String gtin,
            String mpn,
            ProductVariantStatus status,
            String attributeKey,
            String attributeValue) {
    }
}
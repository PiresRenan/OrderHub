package io.github.piresrenan.orderhub.catalog.adapter.out.persistence.postgresql;

import java.util.List;
import java.util.Objects;
import java.util.UUID;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.piresrenan.orderhub.catalog.application.port.out.CatalogMediaRepository;
import io.github.piresrenan.orderhub.catalog.application.port.out.CatalogPersistenceException;
import io.github.piresrenan.orderhub.catalog.domain.model.CatalogMedia;
import io.github.piresrenan.orderhub.catalog.domain.model.CatalogMediaType;

/**
 * PostgreSQL adapter for metadata-only Product and ProductVariant media.
 */
public final class PostgreSqlCatalogMediaRepository
        implements CatalogMediaRepository {

    private static final String UPSERT_SQL = """
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
            ON CONFLICT (tenant_id, id)
            DO UPDATE SET
                product_id = EXCLUDED.product_id,
                variant_id = EXCLUDED.variant_id,
                media_type = EXCLUDED.media_type,
                reference = EXCLUDED.reference,
                alt_text = EXCLUDED.alt_text,
                sort_order = EXCLUDED.sort_order,
                is_primary = EXCLUDED.is_primary
            """;

    private static final String FIND_BY_PRODUCT_SQL = """
            SELECT
                tenant_id,
                id,
                product_id,
                variant_id,
                media_type,
                reference,
                alt_text,
                sort_order,
                is_primary
            FROM catalog.media
            WHERE tenant_id = ?
              AND product_id = ?
            ORDER BY sort_order, id
            """;

    private static final String FIND_BY_VARIANT_SQL = """
            SELECT
                tenant_id,
                id,
                product_id,
                variant_id,
                media_type,
                reference,
                alt_text,
                sort_order,
                is_primary
            FROM catalog.media
            WHERE tenant_id = ?
              AND variant_id = ?
            ORDER BY sort_order, id
            """;

    private final JdbcTemplate jdbcTemplate;

    public PostgreSqlCatalogMediaRepository(
            JdbcTemplate jdbcTemplate) {

        this.jdbcTemplate =
                Objects.requireNonNull(
                        jdbcTemplate,
                        "jdbcTemplate");
    }

    @Override
    public CatalogMedia save(
            CatalogMedia media) {

        try {
            jdbcTemplate.update(
                    UPSERT_SQL,
                    media.tenantId(),
                    media.id(),
                    media.productId(),
                    media.variantId(),
                    media.mediaType().name(),
                    media.reference(),
                    media.altText(),
                    media.sortOrder(),
                    media.primary());

            return media;
        } catch (DataAccessException exception) {
            throw new CatalogPersistenceException(
                    exception);
        }
    }

    @Override
    public List<CatalogMedia> findByProduct(
            UUID tenantId,
            UUID productId) {

        try {
            return jdbcTemplate.query(
                    FIND_BY_PRODUCT_SQL,
                    (resultSet, rowNumber) ->
                            CatalogMedia.forProduct(
                                    resultSet.getObject(
                                            "id",
                                            UUID.class),
                                    resultSet.getObject(
                                            "tenant_id",
                                            UUID.class),
                                    resultSet.getObject(
                                            "product_id",
                                            UUID.class),
                                    CatalogMediaType.valueOf(
                                            resultSet.getString(
                                                    "media_type")),
                                    resultSet.getString(
                                            "reference"),
                                    resultSet.getString(
                                            "alt_text"),
                                    resultSet.getInt(
                                            "sort_order"),
                                    resultSet.getBoolean(
                                            "is_primary")),
                    tenantId,
                    productId);
        } catch (DataAccessException exception) {
            throw new CatalogPersistenceException(
                    exception);
        }
    }

    @Override
    public List<CatalogMedia> findByVariant(
            UUID tenantId,
            UUID variantId) {

        try {
            return jdbcTemplate.query(
                    FIND_BY_VARIANT_SQL,
                    (resultSet, rowNumber) ->
                            CatalogMedia.forVariant(
                                    resultSet.getObject(
                                            "id",
                                            UUID.class),
                                    resultSet.getObject(
                                            "tenant_id",
                                            UUID.class),
                                    resultSet.getObject(
                                            "variant_id",
                                            UUID.class),
                                    CatalogMediaType.valueOf(
                                            resultSet.getString(
                                                    "media_type")),
                                    resultSet.getString(
                                            "reference"),
                                    resultSet.getString(
                                            "alt_text"),
                                    resultSet.getInt(
                                            "sort_order"),
                                    resultSet.getBoolean(
                                            "is_primary")),
                    tenantId,
                    variantId);
        } catch (DataAccessException exception) {
            throw new CatalogPersistenceException(
                    exception);
        }
    }
}
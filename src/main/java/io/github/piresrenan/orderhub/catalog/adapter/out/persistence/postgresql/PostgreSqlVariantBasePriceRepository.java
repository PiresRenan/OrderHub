package io.github.piresrenan.orderhub.catalog.adapter.out.persistence.postgresql;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.piresrenan.orderhub.catalog.application.port.out.CatalogPersistenceException;
import io.github.piresrenan.orderhub.catalog.application.port.out.VariantBasePriceRepository;
import io.github.piresrenan.orderhub.catalog.domain.model.Money;
import io.github.piresrenan.orderhub.catalog.domain.model.VariantBasePrice;

/**
 * PostgreSQL adapter for exact tenant-scoped Variant base prices.
 */
public final class PostgreSqlVariantBasePriceRepository
        implements VariantBasePriceRepository {

    private static final String UPSERT_SQL = """
            INSERT INTO catalog.variant_base_prices (
                tenant_id,
                variant_id,
                currency_code,
                minor_units
            )
            VALUES (?, ?, ?, ?)
            ON CONFLICT (tenant_id, variant_id, currency_code)
            DO UPDATE SET
                minor_units = EXCLUDED.minor_units
            """;

    private static final String FIND_SQL = """
            SELECT
                tenant_id,
                variant_id,
                currency_code,
                minor_units
            FROM catalog.variant_base_prices
            WHERE tenant_id = ?
              AND variant_id = ?
              AND currency_code = ?
            """;

    private final JdbcTemplate jdbcTemplate;

    public PostgreSqlVariantBasePriceRepository(
            JdbcTemplate jdbcTemplate) {

        this.jdbcTemplate =
                Objects.requireNonNull(
                        jdbcTemplate,
                        "jdbcTemplate");
    }

    @Override
    public VariantBasePrice save(
            VariantBasePrice basePrice) {

        try {
            jdbcTemplate.update(
                    UPSERT_SQL,
                    basePrice.tenantId(),
                    basePrice.variantId(),
                    basePrice.currencyCode(),
                    basePrice.minorUnits());

            return basePrice;
        } catch (DataAccessException exception) {
            throw new CatalogPersistenceException(
                    exception);
        }
    }

    @Override
    public Optional<VariantBasePrice> findByVariantAndCurrency(
            UUID tenantId,
            UUID variantId,
            String currencyCode) {

        try {
            var prices =
                    jdbcTemplate.query(
                            FIND_SQL,
                            (resultSet, rowNumber) ->
                                    VariantBasePrice.create(
                                            resultSet.getObject(
                                                    "tenant_id",
                                                    UUID.class),
                                            resultSet.getObject(
                                                    "variant_id",
                                                    UUID.class),
                                            Money.of(
                                                    resultSet.getString(
                                                            "currency_code"),
                                                    resultSet.getLong(
                                                            "minor_units"))),
                            tenantId,
                            variantId,
                            currencyCode);

            return prices.stream()
                    .findFirst();
        } catch (DataAccessException exception) {
            throw new CatalogPersistenceException(
                    exception);
        }
    }
}
package io.github.piresrenan.orderhub.inventory.adapter.out.persistence.postgresql;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.piresrenan.orderhub.inventory.application.port.out.InventoryPersistenceException;
import io.github.piresrenan.orderhub.inventory.application.port.out.InventoryPositionRepository;
import io.github.piresrenan.orderhub.inventory.domain.model.InsufficientInventoryException;
import io.github.piresrenan.orderhub.inventory.domain.model.InventoryAllocation;
import io.github.piresrenan.orderhub.inventory.domain.model.InventoryPolicy;
import io.github.piresrenan.orderhub.inventory.domain.model.InventoryPosition;

/**
 * PostgreSQL adapter for tenant-scoped InventoryPosition persistence and atomic
 * inventory commitment.
 *
 * <p>
 * Correctness under concurrent application instances is provided by
 * PostgreSQL row coordination and single-statement mutations. No process-local
 * locking participates in inventory correctness.
 * </p>
 */
public final class PostgreSqlInventoryPositionRepository
        implements InventoryPositionRepository {

    private static final String FIND_POSITION_SQL = """
            SELECT
                tenant_id,
                variant_id,
                on_hand,
                committed,
                backordered,
                safety_stock
            FROM inventory.inventory_positions
            WHERE tenant_id = ?
              AND variant_id = ?
            """;

    /**
     * DENY is decided by the same SQL statement that mutates committed stock.
     *
     * <p>
     * A row is returned only when the requested quantity is completely covered
     * by available-to-promise stock at the moment PostgreSQL applies the
     * mutation.
     * </p>
     */
    private static final String COMMIT_DENY_SQL = """
            UPDATE inventory.inventory_positions AS inventory_position
            SET committed =
                    inventory_position.committed + ?
            WHERE inventory_position.tenant_id = ?
              AND inventory_position.variant_id = ?
              AND inventory_position.on_hand
                    - inventory_position.committed
                    - inventory_position.safety_stock >= ?
            RETURNING
                tenant_id,
                variant_id,
                on_hand,
                committed,
                backordered,
                safety_stock
            """;

    /**
     * ALLOW_BACKORDER computes both the physically allocable quantity and the
     * uncovered demand inside one PostgreSQL UPDATE.
     *
     * <p>
     * PostgreSQL 18 OLD/NEW RETURNING values provide the exact deltas produced
     * by the row mutation without requiring a preceding or subsequent read.
     * </p>
     */
    private static final String COMMIT_ALLOW_BACKORDER_SQL = """
            WITH demand AS (
                SELECT
                    CAST(? AS BIGINT) AS requested_quantity
            )
            UPDATE inventory.inventory_positions AS inventory_position
            SET
                committed =
                    inventory_position.committed
                    + LEAST(
                        demand.requested_quantity,
                        GREATEST(
                            CAST(0 AS BIGINT),
                            inventory_position.on_hand
                                - inventory_position.committed
                                - inventory_position.safety_stock
                        )
                    ),
                backordered =
                    inventory_position.backordered
                    + (
                        demand.requested_quantity
                        - LEAST(
                            demand.requested_quantity,
                            GREATEST(
                                CAST(0 AS BIGINT),
                                inventory_position.on_hand
                                    - inventory_position.committed
                                    - inventory_position.safety_stock
                            )
                        )
                    )
            FROM demand
            WHERE inventory_position.tenant_id = ?
              AND inventory_position.variant_id = ?
            RETURNING WITH (
                OLD AS before_row,
                NEW AS after_row
            )
                after_row.tenant_id AS tenant_id,
                after_row.variant_id AS variant_id,
                after_row.on_hand AS on_hand,
                after_row.committed AS committed,
                after_row.backordered AS backordered,
                after_row.safety_stock AS safety_stock,
                after_row.committed
                    - before_row.committed
                    AS allocated_quantity,
                after_row.backordered
                    - before_row.backordered
                    AS operation_backordered_quantity
            """;

    private final JdbcTemplate jdbcTemplate;

    public PostgreSqlInventoryPositionRepository(
            JdbcTemplate jdbcTemplate) {

        this.jdbcTemplate =
                Objects.requireNonNull(
                        jdbcTemplate,
                        "jdbcTemplate");
    }

    @Override
    public Optional<InventoryPosition> findById(
            UUID tenantId,
            UUID variantId) {

        try {
            var positions =
                    jdbcTemplate.query(
                            FIND_POSITION_SQL,
                            (resultSet, rowNumber) ->
                                    mapPosition(
                                            resultSet),
                            tenantId,
                            variantId);

            return positions.stream()
                    .findFirst();
        } catch (DataAccessException exception) {
            throw new InventoryPersistenceException(
                    exception);
        }
    }

    @Override
    public InventoryAllocation commit(
            UUID tenantId,
            UUID variantId,
            long requestedQuantity,
            InventoryPolicy policy) {

        validateCommitRequest(
                requestedQuantity,
                policy);

        try {
            return switch (policy) {
                case DENY ->
                        commitDeny(
                                tenantId,
                                variantId,
                                requestedQuantity);

                case ALLOW_BACKORDER ->
                        commitAllowBackorder(
                                tenantId,
                                variantId,
                                requestedQuantity);
            };
        } catch (DataAccessException exception) {
            throw new InventoryPersistenceException(
                    exception);
        }
    }

    private InventoryAllocation commitDeny(
            UUID tenantId,
            UUID variantId,
            long requestedQuantity) {

        var positions =
                jdbcTemplate.query(
                        COMMIT_DENY_SQL,
                        (resultSet, rowNumber) ->
                                mapPosition(
                                        resultSet),
                        requestedQuantity,
                        tenantId,
                        variantId,
                        requestedQuantity);

        var position =
                positions.stream()
                        .findFirst()
                        .orElseThrow(
                                InsufficientInventoryException::new);

        return new InventoryAllocation(
                position,
                requestedQuantity,
                requestedQuantity,
                0L);
    }

    private InventoryAllocation commitAllowBackorder(
            UUID tenantId,
            UUID variantId,
            long requestedQuantity) {

        var allocations =
                jdbcTemplate.query(
                        COMMIT_ALLOW_BACKORDER_SQL,
                        (resultSet, rowNumber) -> {

                            var position =
                                    mapPosition(
                                            resultSet);

                            return new InventoryAllocation(
                                    position,
                                    requestedQuantity,
                                    resultSet.getLong(
                                            "allocated_quantity"),
                                    resultSet.getLong(
                                            "operation_backordered_quantity"));
                        },
                        requestedQuantity,
                        tenantId,
                        variantId);

        return allocations.stream()
                .findFirst()
                .orElseThrow(
                        InsufficientInventoryException::new);
    }

    private static InventoryPosition mapPosition(
            ResultSet resultSet)
            throws SQLException {

        return InventoryPosition.create(
                resultSet.getObject(
                        "tenant_id",
                        UUID.class),
                resultSet.getObject(
                        "variant_id",
                        UUID.class),
                resultSet.getLong(
                        "on_hand"),
                resultSet.getLong(
                        "committed"),
                resultSet.getLong(
                        "backordered"),
                resultSet.getLong(
                        "safety_stock"));
    }

    private static void validateCommitRequest(
            long requestedQuantity,
            InventoryPolicy policy) {

        if (requestedQuantity <= 0) {
            throw new IllegalArgumentException(
                    "Inventory requested quantity must be greater than zero");
        }

        if (policy == null) {
            throw new IllegalArgumentException(
                    "Inventory policy is required");
        }
    }
}
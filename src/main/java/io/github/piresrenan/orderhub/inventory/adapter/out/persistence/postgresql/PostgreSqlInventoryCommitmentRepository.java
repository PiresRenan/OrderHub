package io.github.piresrenan.orderhub.inventory.adapter.out.persistence.postgresql;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.util.Objects;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.piresrenan.orderhub.inventory.application.port.out.InventoryCommitmentRepository;
import io.github.piresrenan.orderhub.inventory.application.port.out.InventoryPersistenceException;
import io.github.piresrenan.orderhub.inventory.domain.model.InventoryCommitment;

/**
 * PostgreSQL adapter for durable Inventory commitment facts.
 */
public final class PostgreSqlInventoryCommitmentRepository
        implements InventoryCommitmentRepository {

    private static final String INSERT_COMMITMENT_SQL = """
            INSERT INTO inventory.inventory_commitments (
                tenant_id,
                commitment_id,
                order_id,
                variant_id,
                requested_quantity,
                allocated_quantity,
                backordered_quantity,
                created_at
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private static final String FIND_BY_ORDER_AND_VARIANT_SQL = """
            SELECT
                tenant_id,
                commitment_id,
                order_id,
                variant_id,
                requested_quantity,
                allocated_quantity,
                backordered_quantity,
                created_at
            FROM inventory.inventory_commitments
            WHERE tenant_id = ?
              AND order_id = ?
              AND variant_id = ?
            """;

    private final JdbcTemplate jdbcTemplate;

    public PostgreSqlInventoryCommitmentRepository(
            JdbcTemplate jdbcTemplate) {

        this.jdbcTemplate =
                Objects.requireNonNull(
                        jdbcTemplate,
                        "jdbcTemplate");
    }

    @Override
    public InventoryCommitment save(
            InventoryCommitment commitment) {

        Objects.requireNonNull(
                commitment,
                "commitment");

        try {
            jdbcTemplate.update(
                    INSERT_COMMITMENT_SQL,
                    commitment.tenantId(),
                    commitment.commitmentId(),
                    commitment.orderId(),
                    commitment.variantId(),
                    commitment.requestedQuantity(),
                    commitment.allocatedQuantity(),
                    commitment.backorderedQuantity(),
                    Timestamp.from(
                            commitment.createdAt()));

            return commitment;
        } catch (DataAccessException exception) {
            throw new InventoryPersistenceException(
                    exception);
        }
    }

    @Override
    public Optional<InventoryCommitment> findByOrderAndVariant(
            UUID tenantId,
            UUID orderId,
            UUID variantId) {

        try {
            var commitments =
                    jdbcTemplate.query(
                            FIND_BY_ORDER_AND_VARIANT_SQL,
                            (resultSet, rowNumber) ->
                                    mapCommitment(
                                            resultSet),
                            tenantId,
                            orderId,
                            variantId);

            return commitments.stream()
                    .findFirst();
        } catch (DataAccessException exception) {
            throw new InventoryPersistenceException(
                    exception);
        }
    }

    private static InventoryCommitment mapCommitment(
            ResultSet resultSet)
            throws SQLException {

        return InventoryCommitment.create(
                resultSet.getObject(
                        "commitment_id",
                        UUID.class),
                resultSet.getObject(
                        "tenant_id",
                        UUID.class),
                resultSet.getObject(
                        "order_id",
                        UUID.class),
                resultSet.getObject(
                        "variant_id",
                        UUID.class),
                resultSet.getLong(
                        "requested_quantity"),
                resultSet.getLong(
                        "allocated_quantity"),
                resultSet.getLong(
                        "backordered_quantity"),
                resultSet.getTimestamp(
                                "created_at")
                        .toInstant());
    }
}
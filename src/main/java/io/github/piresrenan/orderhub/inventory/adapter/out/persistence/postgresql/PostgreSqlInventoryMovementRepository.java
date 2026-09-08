package io.github.piresrenan.orderhub.inventory.adapter.out.persistence.postgresql;

import java.util.Optional;
import java.util.UUID;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import io.github.piresrenan.orderhub.inventory.application.port.in.InventoryAdministrationException;
import io.github.piresrenan.orderhub.inventory.application.port.in.InventoryAdministrationException.Reason;
import io.github.piresrenan.orderhub.inventory.application.port.out.InventoryMovementRepository;
import io.github.piresrenan.orderhub.inventory.domain.model.InventoryMovement;
import io.github.piresrenan.orderhub.inventory.domain.model.InventoryMovementType;

/** PostgreSQL owns operation uniqueness and arithmetic under independent transactions. */
public final class PostgreSqlInventoryMovementRepository implements InventoryMovementRepository {
    private final JdbcTemplate jdbc;
    /** Requires caller-bound JDBC; the repository never starts or commits an independent transaction. */
    public PostgreSqlInventoryMovementRepository(JdbcTemplate jdbc) { this.jdbc=java.util.Objects.requireNonNull(jdbc); }
    /** Acquires durable identity; a conflicting committed command replays or rejects its fingerprint. */
    @Override public Optional<InventoryMovement> acquire(InventoryMovement movement, String fingerprint) {
        requireTransaction();
        try {
            var inserted=jdbc.update("""
                    INSERT INTO inventory.movements(tenant_id,operation_id,actor_user_id,variant_id,movement_type,
                        delta,reason,correlation_id,occurred_at,fingerprint_version,fingerprint)
                    VALUES (?,?,?,?,?,?,?,?,?,1,?) ON CONFLICT (tenant_id,operation_id) DO NOTHING
                    """,movement.tenantId(),movement.operationId(),movement.actorUserId(),movement.variantId(),
                    movement.type().name(),movement.delta(),movement.reason(),movement.correlationId(),
                    Timestamp.from(movement.occurredAt()),fingerprint);
            if(inserted==1) return Optional.empty();
            var previous=jdbc.query("SELECT * FROM inventory.movements WHERE tenant_id=? AND operation_id=?",
                    (row,n)-> {
                        if(row.getInt("fingerprint_version")!=1 || !fingerprint.equals(row.getString("fingerprint")))
                            throw new InventoryAdministrationException(Reason.CONFLICT);
                        return map(row);
                    },movement.tenantId(),movement.operationId());
            return Optional.of(previous.stream().findFirst().orElseThrow(()->new InventoryAdministrationException(Reason.TECHNICAL)));
        } catch(DataAccessException exception) { throw new InventoryAdministrationException(Reason.TECHNICAL,exception); }
    }
    /** Applies an exact bounded delta while preserving all Order-owned commitment counters. */
    @Override public void applyStockDelta(InventoryMovement movement) {
        requireTransaction();
        try {
            if(movement.type()==InventoryMovementType.RECEIPT) {
                jdbc.update("""
                        INSERT INTO inventory.inventory_positions(tenant_id,variant_id,on_hand,committed,backordered,safety_stock)
                        VALUES (?,?,0,0,0,0) ON CONFLICT (tenant_id,variant_id) DO NOTHING
                        """,movement.tenantId(),movement.variantId());
            }
            int changed;
            if(movement.delta()>0) {
                changed=jdbc.update("""
                        UPDATE inventory.inventory_positions SET on_hand=on_hand+?
                        WHERE tenant_id=? AND variant_id=? AND on_hand<=?
                        """,movement.delta(),movement.tenantId(),movement.variantId(),Long.MAX_VALUE-movement.delta());
            } else {
                changed=jdbc.update("""
                        UPDATE inventory.inventory_positions SET on_hand=on_hand+?
                        WHERE tenant_id=? AND variant_id=? AND on_hand-committed>=?
                        """,movement.delta(),movement.tenantId(),movement.variantId(),-movement.delta());
            }
            if(changed!=1) throw new InventoryAdministrationException(Reason.QUANTITY_CONFLICT);
        } catch(DataAccessException exception) { throw new InventoryAdministrationException(Reason.TECHNICAL,exception); }
    }
    /** Rehydrates the stable original result rather than current mutable position state. */
    private static InventoryMovement map(ResultSet row) throws SQLException {
        return new InventoryMovement(row.getObject("tenant_id",UUID.class),row.getObject("operation_id",UUID.class),
                row.getObject("actor_user_id",UUID.class),row.getObject("variant_id",UUID.class),
                InventoryMovementType.valueOf(row.getString("movement_type")),row.getLong("delta"),row.getString("reason"),
                row.getObject("correlation_id",UUID.class),row.getTimestamp("occurred_at").toInstant());
    }
    /** Rejects autocommit so evidence and stock cannot become independently durable. */
    private static void requireTransaction() {
        if(!TransactionSynchronizationManager.isActualTransactionActive()) throw new InventoryAdministrationException(Reason.TECHNICAL);
    }
}

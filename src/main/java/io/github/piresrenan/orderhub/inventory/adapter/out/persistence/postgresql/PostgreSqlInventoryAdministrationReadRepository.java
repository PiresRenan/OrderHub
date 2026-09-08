package io.github.piresrenan.orderhub.inventory.adapter.out.persistence.postgresql;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import org.springframework.jdbc.core.JdbcTemplate;
import io.github.piresrenan.orderhub.inventory.application.port.out.InventoryAdministrationReadRepository;
import io.github.piresrenan.orderhub.inventory.domain.model.*;
/** Tenant/index predicates bound every statement; cursor ordering is PostgreSQL UUID ordering. */
public final class PostgreSqlInventoryAdministrationReadRepository implements InventoryAdministrationReadRepository {
    private final JdbcTemplate jdbc;
    public PostgreSqlInventoryAdministrationReadRepository(JdbcTemplate jdbc) { this.jdbc=java.util.Objects.requireNonNull(jdbc); }
    @Override public Optional<InventoryPosition> position(UUID tenant,UUID variant) {
        return jdbc.query("SELECT tenant_id,variant_id,on_hand,committed,backordered,safety_stock FROM inventory.inventory_positions WHERE tenant_id=? AND variant_id=?",
                (row,n)->position(row),tenant,variant).stream().findFirst();
    }
    @Override public Optional<InventoryPolicy> policy(UUID tenant) {
        return jdbc.query("SELECT policy FROM inventory.tenant_policies WHERE tenant_id=?",
                (row,n)->InventoryPolicy.valueOf(row.getString(1)),tenant).stream().findFirst();
    }
    @Override public List<InventoryPosition> positions(UUID tenant,UUID afterVariant,int limit) {
        return jdbc.query("SELECT tenant_id,variant_id,on_hand,committed,backordered,safety_stock FROM inventory.inventory_positions WHERE tenant_id=?"
                +(afterVariant==null?"":" AND variant_id>?")+" ORDER BY variant_id LIMIT ?",
                (row,n)->position(row),afterVariant==null?new Object[]{tenant,limit}:new Object[]{tenant,afterVariant,limit});
    }
    @Override public List<InventoryMovement> movements(UUID tenant,UUID variant,UUID afterOperation,int limit) {
        return jdbc.query("""
                SELECT tenant_id,operation_id,actor_user_id,variant_id,movement_type,delta,reason,correlation_id,occurred_at
                FROM inventory.movements WHERE tenant_id=? AND variant_id=?
                """+(afterOperation==null?"":" AND operation_id>?")+" ORDER BY operation_id LIMIT ?",
                (row,n)->new InventoryMovement(row.getObject("tenant_id",UUID.class),row.getObject("operation_id",UUID.class),
                        row.getObject("actor_user_id",UUID.class),row.getObject("variant_id",UUID.class),InventoryMovementType.valueOf(row.getString("movement_type")),
                        row.getLong("delta"),row.getString("reason"),row.getObject("correlation_id",UUID.class),row.getTimestamp("occurred_at").toInstant()),
                afterOperation==null?new Object[]{tenant,variant,limit}:new Object[]{tenant,variant,afterOperation,limit});
    }
    private static InventoryPosition position(ResultSet row) throws SQLException {
        return InventoryPosition.create(row.getObject("tenant_id",UUID.class),row.getObject("variant_id",UUID.class),
                row.getLong("on_hand"),row.getLong("committed"),row.getLong("backordered"),row.getLong("safety_stock"));
    }
}

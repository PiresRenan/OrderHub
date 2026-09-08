package io.github.piresrenan.orderhub.inventory.adapter.out.persistence.postgresql;
import org.springframework.jdbc.core.JdbcTemplate;
import io.github.piresrenan.orderhub.inventory.application.port.out.InventoryPolicyAdministrationRepository;
import java.util.UUID;
import java.util.Optional;
import java.time.Instant;
import java.sql.Timestamp;
import io.github.piresrenan.orderhub.inventory.domain.model.*;
/** PostgreSQL implementation of owner-local policy state and evidence. */
public final class PostgreSqlInventoryPolicyAdministrationRepository implements InventoryPolicyAdministrationRepository {
    private final JdbcTemplate jdbc;
    /** Requires the existing transaction-bound JDBC adapter. */
    public PostgreSqlInventoryPolicyAdministrationRepository(JdbcTemplate jdbc) { this.jdbc=java.util.Objects.requireNonNull(jdbc); }
    /** Stabilizes current policy until the authoritative transaction finishes. */
    @Override public Optional<InventoryPolicy> lockPolicy(UUID tenant) {
        return jdbc.query("SELECT policy FROM inventory.tenant_policies WHERE tenant_id=? FOR UPDATE",
                (row,n)->InventoryPolicy.valueOf(row.getString(1)),tenant).stream().findFirst();
    }
    /** Serializes concurrent policy initialization through its unique Tenant key. */
    @Override public boolean initializePolicy(UUID tenant,InventoryPolicy desired) {
        return jdbc.update("INSERT INTO inventory.tenant_policies(tenant_id,policy) VALUES (?,?) ON CONFLICT (tenant_id) DO NOTHING",
                tenant,desired.name())==1;
    }
    /** Saves only the already locked policy row. */
    @Override public void savePolicy(UUID tenant,InventoryPolicy desired) {
        jdbc.update("UPDATE inventory.tenant_policies SET policy=? WHERE tenant_id=?",desired.name(),tenant);
    }
    /** Acquires the same Position row that atomic Order commitment mutates. */
    @Override public Optional<InventoryPosition> lockPosition(UUID tenant,UUID variant) {
        return jdbc.query("SELECT * FROM inventory.inventory_positions WHERE tenant_id=? AND variant_id=? FOR UPDATE",
                (row,n)->InventoryPosition.create(tenant,variant,row.getLong("on_hand"),row.getLong("committed"),
                        row.getLong("backordered"),row.getLong("safety_stock")),tenant,variant).stream().findFirst();
    }
    /** Alters safety alone, leaving all physical allocation counters unchanged. */
    @Override public void saveSafety(UUID tenant,UUID variant,long desired) {
        jdbc.update("UPDATE inventory.inventory_positions SET safety_stock=? WHERE tenant_id=? AND variant_id=?",desired,tenant,variant);
    }
    /** Persists immutable policy transition evidence without an independent transaction. */
    @Override public void appendPolicy(UUID actor,UUID tenant,InventoryPolicy before,InventoryPolicy after,String reason,UUID correlation,Instant time) {
        jdbc.update("""
                INSERT INTO inventory.policy_changes(tenant_id,change_id,actor_user_id,action,before_policy,after_policy,reason,correlation_id,occurred_at)
                VALUES (?,?,?,'OVERSELL_POLICY',?,?,?,?,?)
                """,tenant,UUID.randomUUID(),actor,before==null?null:before.name(),after.name(),reason,correlation,Timestamp.from(time));
    }
    /** Persists immutable safety transition evidence without duplicating movement facts. */
    @Override public void appendSafety(UUID actor,UUID tenant,UUID variant,long before,long after,String reason,UUID correlation,Instant time) {
        jdbc.update("""
                INSERT INTO inventory.policy_changes(tenant_id,change_id,actor_user_id,variant_id,action,before_safety_stock,after_safety_stock,reason,correlation_id,occurred_at)
                VALUES (?,?,?,?,'SAFETY_STOCK',?,?,?,?,?)
                """,tenant,UUID.randomUUID(),actor,variant,before,after,reason,correlation,Timestamp.from(time));
    }
}

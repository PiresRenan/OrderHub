package io.github.piresrenan.orderhub.users.adapter.out.persistence.postgresql;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import io.github.piresrenan.orderhub.users.application.port.out.TenantMembershipPersistenceException;
import io.github.piresrenan.orderhub.users.application.port.out.TenantMembershipTransitionRepository;
import io.github.piresrenan.orderhub.users.domain.model.TenantMembershipStatus;

/** Mutation and evidence participate in the coordinator's same physical database transaction. */
public final class PostgreSqlTenantMembershipTransitionRepository implements TenantMembershipTransitionRepository {
    private final JdbcTemplate jdbc;
    /** Requires the supplied owner contracts; construction performs no lifecycle mutation or independent commit. */
    public PostgreSqlTenantMembershipTransitionRepository(JdbcTemplate jdbc) { this.jdbc = Objects.requireNonNull(jdbc); }

    /** Locks the exact membership before evaluating a lifecycle transition, including terminal-state retries. */
    @Override public Optional<TenantMembershipStatus> lock(UUID tenant, UUID subject) {
        return guarded(() -> jdbc.query("SELECT status FROM users.tenant_memberships WHERE tenant_id = ? AND user_id = ? FOR UPDATE",
                (row, n) -> TenantMembershipStatus.valueOf(row.getString(1)), tenant, subject).stream().findFirst());
    }
    /** Changes the locked membership and appends its evidence in one physical transaction. */
    @Override public void change(UUID actor, UUID tenant, UUID subject, String action, TenantMembershipStatus before, TenantMembershipStatus after, UUID correlation) {
        guarded(() -> {
            if (jdbc.update("UPDATE users.tenant_memberships SET status = ? WHERE tenant_id = ? AND user_id = ? AND status = ?",
                    after.name(), tenant, subject, before.name()) != 1) { throw new IllegalStateException("Membership transition lost its locked state"); }
            jdbc.update("""
                    INSERT INTO users.tenant_membership_events (event_id, tenant_id, actor_user_id, subject_user_id, action, before_status, after_status, correlation_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), tenant, actor, subject, action, before.name(), after.name(), correlation);
            return null;
        });
    }
    /** Requires the owner database transaction and preserves technical failure separately from policy denial. */
    private <T> T guarded(Supplier<T> work) {
        if (!TransactionSynchronizationManager.isActualTransactionActive() || jdbc.getDataSource() == null
                || !TransactionSynchronizationManager.hasResource(jdbc.getDataSource())) {
            throw new IllegalStateException("Membership transition requires its database transaction");
        }
        try { return work.get(); }
        catch (DataAccessException exception) { throw new TenantMembershipPersistenceException(exception); }
    }
}

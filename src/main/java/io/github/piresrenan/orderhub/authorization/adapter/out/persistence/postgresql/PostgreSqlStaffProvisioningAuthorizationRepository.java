package io.github.piresrenan.orderhub.authorization.adapter.out.persistence.postgresql;

import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import io.github.piresrenan.orderhub.authorization.application.port.out.AuthorizationPersistenceException;
import io.github.piresrenan.orderhub.authorization.application.port.out.StaffProvisioningAuthorizationRepository;

/**
 * Stabilizes owner-local authorization state without suspending the outer write.
 * Actor/target scopes serialize only their own assignments and overrides. Shared
 * metadata locks allow concurrent provisioning but block role-definition edits
 * until every provisioning decision using that metadata has completed.
 */
public final class PostgreSqlStaffProvisioningAuthorizationRepository implements StaffProvisioningAuthorizationRepository {
    private final JdbcTemplate jdbc;

    /** Uses the DataSource and physical transaction supplied by runtime composition. */
    public PostgreSqlStaffProvisioningAuthorizationRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /** Holds the exact actor scope and shared definition facts until outer commit or rollback. */
    @Override
    public void lock(UUID userId, UUID tenantId) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(tenantId, "tenantId");
        requireTransaction();
        try {
            jdbc.execute("LOCK TABLE access_control.role_definitions, access_control.role_permissions IN SHARE MODE");
            jdbc.query("SELECT access_control.acquire_staff_provisioning_authority_lock(?, ?)",
                    (row, index) -> null, userId, tenantId);
        } catch (DataAccessException exception) {
            throw new AuthorizationPersistenceException(exception);
        }
    }

    /** Appends audit only after the role outcome has been determined in the same transaction. */
    @Override
    public void append(UUID actorUserId, UUID tenantId, UUID targetUserId, String roleCode,
            UUID intentId, UUID correlationId, boolean changed) {
        requireTransaction();
        try {
            jdbc.update("""
                    INSERT INTO access_control.staff_provisioning_role_events
                        (event_id, actor_user_id, tenant_id, target_user_id, role_code, intent_id, correlation_id, changed)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), actorUserId, tenantId, targetUserId, roleCode, intentId, correlationId, changed);
        } catch (DataAccessException exception) {
            throw new AuthorizationPersistenceException(exception);
        }
    }

    /** Rejects accidental autocommit or a transaction associated with a different DataSource. */
    private void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.hasResource(Objects.requireNonNull(jdbc.getDataSource(), "dataSource"))) {
            throw new IllegalStateException("Role provisioning requires its authoritative transaction");
        }
    }
}

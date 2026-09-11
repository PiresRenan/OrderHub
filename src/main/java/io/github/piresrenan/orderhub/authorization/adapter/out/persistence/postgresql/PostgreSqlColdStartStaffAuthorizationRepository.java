package io.github.piresrenan.orderhub.authorization.adapter.out.persistence.postgresql;

import java.util.Objects;
import java.util.Set;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import io.github.piresrenan.orderhub.authorization.application.port.out.AuthorizationPersistenceException;
import io.github.piresrenan.orderhub.authorization.application.port.out.ColdStartStaffAuthorizationRepository;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionCode;

/** Holds the real Platform grant; never fabricates a Staff actor or writes foreign state. */
public final class PostgreSqlColdStartStaffAuthorizationRepository implements ColdStartStaffAuthorizationRepository {
    private final JdbcTemplate jdbc;

    /** Requires the supplied owner contracts; construction performs no lifecycle mutation or independent commit. */
    public PostgreSqlColdStartStaffAuthorizationRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /** Holds the actual Platform grant through the ceremony; absence never becomes Tenant authority. */
    @Override public boolean holdPlatformManagerGrant(UUID actorUserId) {
        requireTransaction();
        try {
            return !jdbc.query("""
                    SELECT grant_id FROM access_control.administrative_grants
                    WHERE user_id = ? AND scope_type = 'PLATFORM' AND scope_id IS NULL
                      AND permission_code = 'PLATFORM_TENANTS_MANAGE' FOR SHARE
                    """, (row, index) -> row.getObject("grant_id", UUID.class), actorUserId).isEmpty();
        } catch (DataAccessException exception) {
            throw new AuthorizationPersistenceException(exception);
        }
    }

    /** Acquires the catalog lock before role insertion to avoid shared-lock upgrade races. */
    @Override public void lockRoleCatalog() {
        requireTransaction();
        try {
            // Acquire the creation-compatible lock before ordinary shared role
            // locks, avoiding concurrent shared-to-exclusive upgrades.
            jdbc.execute("LOCK TABLE access_control.role_definitions, access_control.role_permissions IN SHARE ROW EXCLUSIVE MODE");
        } catch (DataAccessException exception) {
            throw new AuthorizationPersistenceException(exception);
        }
    }

    /** Creates only the explicit v1 governance role; all writes remain in the caller transaction. */
    @Override public void createGovernanceRole(UUID tenantId, String code, Set<PermissionCode> permissions) {
        requireTransaction();
        try {
            var roleId = UUID.randomUUID();
            jdbc.update("""
                    INSERT INTO access_control.role_definitions (role_id, tenant_id, code, persona, authority_band, mutability)
                    VALUES (?, ?, ?, 'STAFF', 'TENANT_GOVERNANCE', 'TENANT_CUSTOM')
                    """, roleId, tenantId, code);
            for (var permission : permissions) {
                jdbc.update("INSERT INTO access_control.role_permissions (role_id, permission_code) VALUES (?, ?)", roleId, permission.name());
            }
        } catch (DataAccessException exception) {
            throw new AuthorizationPersistenceException(exception);
        }
    }

    /** Rejects autocommit or an unrelated DataSource before authoritative state can be changed. */
    private void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.hasResource(Objects.requireNonNull(jdbc.getDataSource(), "dataSource"))) {
            throw new IllegalStateException("Cold-start authority requires its authoritative transaction");
        }
    }
}

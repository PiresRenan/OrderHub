package io.github.piresrenan.orderhub.workforce.adapter.out.persistence.postgresql;

import java.util.Objects;
import java.util.Optional;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import io.github.piresrenan.orderhub.authorization.application.port.in.provisioning.StaffProvisioningActor;
import io.github.piresrenan.orderhub.authorization.application.port.in.provisioning.StaffProvisioningTarget;
import io.github.piresrenan.orderhub.authorization.domain.model.AuthorityBand;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionCode;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionEnvelope;
import io.github.piresrenan.orderhub.workforce.application.port.out.StaffProvisioningFactsRepository;
import io.github.piresrenan.orderhub.workforce.application.port.out.StaffProvisioningIntentPersistenceException;

/**
 * Locks current workforce facts until outer provisioning completion. Position
 * FOR UPDATE also blocks foreign-key-backed permission insertion; existing
 * permission rows are share-locked against removal or replacement.
 */
public final class PostgreSqlStaffProvisioningFactsRepository implements StaffProvisioningFactsRepository {
    private final JdbcTemplate jdbc;

    /** Uses only workforce-owned persistence on the shared runtime DataSource. */
    public PostgreSqlStaffProvisioningFactsRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /** Resolves current ACTIVE Staff with an exact placement, never a supplied ceiling. */
    @Override
    public Optional<StaffProvisioningActor> actor(UUID userId, UUID tenantId) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(tenantId, "tenantId");
        requireTransaction();
        try {
            var rows = jdbc.query("""
                    SELECT position.position_id, position.authority_band
                    FROM workforce.staff_profiles staff
                    JOIN workforce.staff_placements placement
                        ON placement.tenant_id = staff.tenant_id AND placement.staff_id = staff.staff_id
                    JOIN workforce.job_positions position
                        ON position.tenant_id = placement.tenant_id AND position.position_id = placement.position_id
                    WHERE staff.user_id = ? AND staff.tenant_id = ? AND staff.status = 'ACTIVE'
                    FOR UPDATE OF staff, placement, position
                    """, (row, index) -> new Position(row.getObject("position_id", UUID.class),
                    AuthorityBand.valueOf(row.getString("authority_band"))), userId, tenantId);
            if (rows.isEmpty()) { return Optional.empty(); }
            var position = rows.getFirst();
            return Optional.of(new StaffProvisioningActor(userId, tenantId, position.band(), permissions(tenantId, position.id())));
        } catch (DataAccessException | IllegalArgumentException exception) {
            throw new StaffProvisioningIntentPersistenceException("Workforce provisioning facts are unavailable", exception);
        }
    }

    /** Validates and holds both same-Tenant placement references before materialization. */
    @Override
    public Optional<StaffProvisioningTarget> target(UUID tenantId, UUID departmentId, UUID positionId) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(departmentId, "departmentId");
        Objects.requireNonNull(positionId, "positionId");
        requireTransaction();
        try {
            var bands = jdbc.query("""
                    SELECT position.authority_band
                    FROM workforce.job_positions position
                    JOIN workforce.departments department ON department.tenant_id = position.tenant_id
                    WHERE position.tenant_id = ? AND position.position_id = ? AND department.department_id = ?
                    FOR UPDATE OF position FOR SHARE OF department
                    """, (row, index) -> AuthorityBand.valueOf(row.getString("authority_band")), tenantId, positionId, departmentId);
            if (bands.isEmpty()) { return Optional.empty(); }
            return Optional.of(new StaffProvisioningTarget(bands.getFirst(), permissions(tenantId, positionId)));
        } catch (DataAccessException | IllegalArgumentException exception) {
            throw new StaffProvisioningIntentPersistenceException("Workforce provisioning facts are unavailable", exception);
        }
    }

    /** Locks every existing permission member after its parent position has been stabilized. */
    private PermissionEnvelope permissions(UUID tenantId, UUID positionId) {
        return PermissionEnvelope.of(jdbc.query("""
                SELECT permission_code FROM workforce.job_position_permissions
                WHERE tenant_id = ? AND position_id = ? ORDER BY permission_code FOR SHARE
                """, (row, index) -> PermissionCode.valueOf(row.getString("permission_code")), tenantId, positionId));
    }

    /** Prevents a caller from mistaking autocommit reads for held authoritative locks. */
    private void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.hasResource(Objects.requireNonNull(jdbc.getDataSource(), "dataSource"))) {
            throw new IllegalStateException("Workforce provisioning facts require their authoritative transaction");
        }
    }

    private record Position(UUID id, AuthorityBand band) {}
}

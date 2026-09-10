package io.github.piresrenan.orderhub.workforce.adapter.out.persistence.postgresql;

import java.util.Objects;
import java.util.UUID;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import io.github.piresrenan.orderhub.workforce.application.port.out.StaffMaterializationConflictException;
import io.github.piresrenan.orderhub.workforce.application.port.out.StaffMaterializationPersistenceException;
import io.github.piresrenan.orderhub.workforce.application.port.out.StaffMaterializationRepository;

/**
 * Materializes only workforce-owned rows. PostgreSQL uniqueness arbitrates
 * competing creation; row locks keep an existing desired state stable until
 * the outer provisioning transaction ends. This adapter never commits itself.
 */
public final class PostgreSqlStaffMaterializationRepository implements StaffMaterializationRepository {
    private final JdbcTemplate jdbc;

    /** Uses the same data source as the authoritative provisioning transaction. */
    public PostgreSqlStaffMaterializationRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /** Creates the exact pair or verifies an existing pair without mutating it. */
    @Override
    public UUID materialize(UUID tenantId, UUID userId, UUID departmentId, UUID positionId) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(departmentId, "departmentId");
        Objects.requireNonNull(positionId, "positionId");
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.hasResource(Objects.requireNonNull(jdbc.getDataSource(), "dataSource"))) {
            throw new IllegalStateException("Staff materialization requires an authoritative transaction");
        }
        try {
            var created = jdbc.query("""
                    INSERT INTO workforce.staff_profiles (staff_id, user_id, tenant_id, status)
                    VALUES (?, ?, ?, 'ACTIVE')
                    ON CONFLICT (user_id, tenant_id) DO NOTHING
                    RETURNING staff_id
                    """, (row, index) -> row.getObject("staff_id", UUID.class),
                    UUID.randomUUID(), userId, tenantId);
            if (!created.isEmpty()) {
                var staffId = created.getFirst();
                jdbc.update("""
                        INSERT INTO workforce.staff_placements (tenant_id, staff_id, department_id, position_id)
                        VALUES (?, ?, ?, ?)
                        """, tenantId, staffId, departmentId, positionId);
                return staffId;
            }
            var existing = jdbc.query("""
                    SELECT staff_id, status FROM workforce.staff_profiles
                    WHERE tenant_id = ? AND user_id = ? FOR UPDATE
                    """, (row, index) -> new Staff(row.getObject("staff_id", UUID.class), row.getString("status")),
                    tenantId, userId);
            if (existing.size() != 1 || !existing.getFirst().status().equals("ACTIVE")) {
                throw new StaffMaterializationConflictException();
            }
            var staffId = existing.getFirst().id();
            var placements = jdbc.query("""
                    SELECT department_id, position_id FROM workforce.staff_placements
                    WHERE tenant_id = ? AND staff_id = ? FOR UPDATE
                    """, (row, index) -> departmentId.equals(row.getObject("department_id", UUID.class))
                            && positionId.equals(row.getObject("position_id", UUID.class)), tenantId, staffId);
            if (placements.size() != 1 || !placements.getFirst()) {
                throw new StaffMaterializationConflictException();
            }
            return staffId;
        } catch (DataAccessException exception) {
            throw new StaffMaterializationPersistenceException(exception);
        }
    }

    private record Staff(UUID id, String status) {}
}

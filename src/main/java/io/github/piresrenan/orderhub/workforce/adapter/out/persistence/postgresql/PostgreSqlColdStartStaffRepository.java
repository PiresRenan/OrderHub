package io.github.piresrenan.orderhub.workforce.adapter.out.persistence.postgresql;

import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionEnvelope;
import io.github.piresrenan.orderhub.workforce.application.model.ColdStartStaffPlacement;
import io.github.piresrenan.orderhub.workforce.application.port.out.ColdStartStaffRepository;
import io.github.piresrenan.orderhub.workforce.application.port.out.StaffProvisioningIntentPersistenceException;
import io.github.piresrenan.orderhub.workforce.application.service.StaffProvisioningUnavailableException;

/** Serializes first-Staff attempts using the existing owner-local Tenant governance lock. */
public final class PostgreSqlColdStartStaffRepository implements ColdStartStaffRepository {
    private static final String CODE = "INITIAL_GOVERNANCE_V1";
    private final JdbcTemplate jdbc;

    public PostgreSqlColdStartStaffRepository(JdbcTemplate jdbc) { this.jdbc = Objects.requireNonNull(jdbc, "jdbc"); }

    @Override public void lockEmptyTenant(UUID tenantId) {
        requireTransaction();
        try {
            jdbc.queryForObject("SELECT workforce.acquire_governance_tenant_lock(?)", Object.class, tenantId);
            var used = jdbc.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM workforce.staff_profiles WHERE tenant_id = ?)
                        OR EXISTS (SELECT 1 FROM workforce.provisioning_events issued
                            JOIN workforce.provisioning_events consumed ON consumed.intent_id = issued.intent_id
                            WHERE issued.tenant_id = ? AND issued.action = 'COLD_START_ISSUED' AND consumed.action = 'CONSUMED')
                    """, Boolean.class, tenantId, tenantId);
            if (Boolean.TRUE.equals(used)) { throw new StaffProvisioningUnavailableException(); }
        } catch (DataAccessException exception) {
            throw new StaffProvisioningIntentPersistenceException("Cold-start workforce state is unavailable", exception);
        }
    }

    @Override public ColdStartStaffPlacement prepare(UUID tenantId, PermissionEnvelope envelope) {
        lockEmptyTenant(tenantId);
        try {
            jdbc.update("INSERT INTO workforce.departments (department_id, tenant_id, code, name) VALUES (?, ?, ?, 'Initial Tenant governance') ON CONFLICT (tenant_id, code) DO NOTHING",
                    UUID.randomUUID(), tenantId, CODE);
            var department = jdbc.queryForObject("SELECT department_id FROM workforce.departments WHERE tenant_id = ? AND code = ? FOR SHARE",
                    UUID.class, tenantId, CODE);
            var created = jdbc.query("""
                    INSERT INTO workforce.job_positions (position_id, tenant_id, code, title, authority_band)
                    VALUES (?, ?, ?, 'Initial Tenant governor', 'TENANT_GOVERNANCE')
                    ON CONFLICT (tenant_id, code) DO NOTHING RETURNING position_id
                    """, (row, index) -> row.getObject("position_id", UUID.class), UUID.randomUUID(), tenantId, CODE);
            var position = jdbc.queryForObject("SELECT position_id FROM workforce.job_positions WHERE tenant_id = ? AND code = ? AND authority_band = 'TENANT_GOVERNANCE' FOR UPDATE",
                    UUID.class, tenantId, CODE);
            if (!created.isEmpty()) {
                for (var permission : envelope.permissions()) {
                    jdbc.update("INSERT INTO workforce.job_position_permissions (tenant_id, position_id, permission_code) VALUES (?, ?, ?)", tenantId, position, permission.name());
                }
            }
            var actual = jdbc.query("SELECT permission_code FROM workforce.job_position_permissions WHERE tenant_id = ? AND position_id = ? FOR SHARE",
                    (row, index) -> row.getString("permission_code"), tenantId, position);
            var expected = envelope.permissions().stream().map(Enum::name).collect(java.util.stream.Collectors.toSet());
            if (!java.util.Set.copyOf(actual).equals(expected)) { throw new StaffProvisioningUnavailableException(); }
            return new ColdStartStaffPlacement(department, position);
        } catch (DataAccessException exception) {
            throw new StaffProvisioningIntentPersistenceException("Cold-start placement is unavailable", exception);
        }
    }

    private void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.hasResource(Objects.requireNonNull(jdbc.getDataSource(), "dataSource"))) {
            throw new IllegalStateException("Cold-start workforce requires its authoritative transaction");
        }
    }
}

package io.github.piresrenan.orderhub.workforce.adapter.out.persistence.postgresql;

import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import io.github.piresrenan.orderhub.authorization.domain.model.AuthorityBand;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionCode;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionEnvelope;
import io.github.piresrenan.orderhub.workforce.application.port.out.TenantMembershipAdministrationFacts;
import io.github.piresrenan.orderhub.workforce.application.port.out.WorkforcePermissionEnvelopeRepository;
import io.github.piresrenan.orderhub.workforce.application.port.in.authorization.StaffAuthorizationUnavailableException;

/** Serializes lifecycle administrators without inverting provisioning's Staff-row lock order. */
public final class PostgreSqlTenantMembershipAdministrationFacts implements TenantMembershipAdministrationFacts {
    private final JdbcTemplate jdbc;
    private final WorkforcePermissionEnvelopeRepository envelopes;
    public PostgreSqlTenantMembershipAdministrationFacts(JdbcTemplate jdbc, WorkforcePermissionEnvelopeRepository envelopes) {
        this.jdbc = Objects.requireNonNull(jdbc); this.envelopes = Objects.requireNonNull(envelopes);
    }
    @Override public void lockTenant(UUID tenant) {
        if (!TransactionSynchronizationManager.isActualTransactionActive() || jdbc.getDataSource() == null
                || !TransactionSynchronizationManager.hasResource(jdbc.getDataSource())) {
            throw new IllegalStateException("Membership administration requires its database transaction");
        }
        try { jdbc.queryForObject("SELECT workforce.acquire_governance_tenant_lock(?)", Object.class, tenant); }
        catch (DataAccessException exception) { throw new StaffAuthorizationUnavailableException(exception); }
    }
    @Override public PermissionEnvelope envelope(UUID actor, UUID tenant, UUID subject) {
        var actorEnvelope = envelopes.find(actor, tenant);
        if (subject == null || actorEnvelope.permissions().isEmpty()) { return actorEnvelope; }
        try {
            var target = jdbc.query("""
                    SELECT position.position_id, position.authority_band
                    FROM workforce.staff_profiles staff
                    LEFT JOIN workforce.staff_placements placement ON placement.tenant_id = staff.tenant_id AND placement.staff_id = staff.staff_id
                    LEFT JOIN workforce.job_positions position ON position.tenant_id = placement.tenant_id AND position.position_id = placement.position_id
                    WHERE staff.tenant_id = ? AND staff.user_id = ?
                    """, (row, n) -> new Position(row.getObject("position_id", UUID.class), row.getString("authority_band")), tenant, subject);
            if (target.isEmpty()) { return actorEnvelope; }
            var selected = target.getFirst();
            if (selected.id() == null) { return PermissionEnvelope.none(); }
            var actorBands = jdbc.query("""
                    SELECT position.authority_band FROM workforce.staff_profiles staff
                    JOIN workforce.staff_placements placement ON placement.tenant_id = staff.tenant_id AND placement.staff_id = staff.staff_id
                    JOIN workforce.job_positions position ON position.tenant_id = placement.tenant_id AND position.position_id = placement.position_id
                    WHERE staff.tenant_id = ? AND staff.user_id = ? AND staff.status = 'ACTIVE'
                    """, (row, n) -> AuthorityBand.valueOf(row.getString(1)), tenant, actor);
            if (actorBands.isEmpty() || !actorBands.getFirst().isAtLeast(AuthorityBand.valueOf(selected.band()))) { return PermissionEnvelope.none(); }
            var targetPermissions = jdbc.query("SELECT permission_code FROM workforce.job_position_permissions WHERE tenant_id = ? AND position_id = ?",
                    (row, n) -> PermissionCode.valueOf(row.getString(1)), tenant, selected.id());
            return actorEnvelope.containsAll(targetPermissions) ? actorEnvelope : PermissionEnvelope.none();
        } catch (DataAccessException | IllegalArgumentException exception) { throw new StaffAuthorizationUnavailableException(exception); }
    }
    private record Position(UUID id, String band) {}
}

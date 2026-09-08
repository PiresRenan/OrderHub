package io.github.piresrenan.orderhub.workforce.adapter.out.persistence.postgresql;

import java.util.Objects;
import java.util.UUID;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.piresrenan.orderhub.authorization.domain.model.AuthorityBand;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionCode;
import io.github.piresrenan.orderhub.authorization.domain.model.PermissionEnvelope;
import io.github.piresrenan.orderhub.workforce.application.port.in.authorization.StaffAuthorizationUnavailableException;
import io.github.piresrenan.orderhub.workforce.application.port.out.WorkforcePermissionEnvelopeRepository;
import io.github.piresrenan.orderhub.workforce.domain.model.Department;
import io.github.piresrenan.orderhub.workforce.domain.model.JobPosition;
import io.github.piresrenan.orderhub.workforce.domain.model.StaffPlacement;
import io.github.piresrenan.orderhub.workforce.domain.model.StaffProfile;
import io.github.piresrenan.orderhub.workforce.domain.model.StaffStatus;
import io.github.piresrenan.orderhub.workforce.domain.policy.WorkforceAuthorityResolver;

/**
 * Reads only workforce-owned current authority. The exact User/Tenant unique key
 * bounds the placement lookup to one row; permission membership belongs to that
 * single position. Both queries participate in the kernel's caller-owned snapshot.
 */
public final class PostgreSqlWorkforcePermissionEnvelopeRepository
        implements WorkforcePermissionEnvelopeRepository {

    private final JdbcTemplate jdbc;
    private final WorkforceAuthorityResolver resolver = new WorkforceAuthorityResolver();

    /** Uses caller-bound JDBC connections; no independent transaction or row lock is created. */
    public PostgreSqlWorkforcePermissionEnvelopeRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /** Rehydrates the existing workforce model and resolves its ceiling for the exact actor. */
    @Override
    public PermissionEnvelope find(UUID userId, UUID tenantId) {
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(tenantId, "tenantId");
        try {
            var placements = jdbc.query("""
                    SELECT staff.staff_id, staff.status,
                           department.department_id, department.code AS department_code,
                           department.name AS department_name,
                           position.position_id, position.code AS position_code,
                           position.title, position.authority_band
                    FROM workforce.staff_profiles staff
                    JOIN workforce.staff_placements placement
                      ON placement.tenant_id = staff.tenant_id AND placement.staff_id = staff.staff_id
                    JOIN workforce.job_positions position
                      ON position.tenant_id = placement.tenant_id AND position.position_id = placement.position_id
                    JOIN workforce.departments department
                      ON department.tenant_id = placement.tenant_id AND department.department_id = placement.department_id
                    WHERE staff.user_id = ? AND staff.tenant_id = ?
                    """, (row, number) -> new CurrentPlacement(
                            new StaffProfile(row.getObject("staff_id", UUID.class), userId, tenantId,
                                    StaffStatus.valueOf(row.getString("status"))),
                            new Department(row.getObject("department_id", UUID.class), tenantId,
                                    row.getString("department_code"), row.getString("department_name")),
                            row.getObject("position_id", UUID.class), row.getString("position_code"),
                            row.getString("title"), AuthorityBand.valueOf(row.getString("authority_band"))),
                    userId, tenantId);
            if (placements.isEmpty()) {
                return PermissionEnvelope.none();
            }
            var current = placements.getFirst();
            if (!current.staff().isActive()) {
                return PermissionEnvelope.none();
            }
            var permissions = jdbc.query("""
                    SELECT permission_code FROM workforce.job_position_permissions
                    WHERE tenant_id = ? AND position_id = ? ORDER BY permission_code
                    """, (row, number) -> PermissionCode.valueOf(row.getString("permission_code")),
                    tenantId, current.positionId());
            var position = new JobPosition(current.positionId(), tenantId, current.code(),
                    current.title(), current.authorityBand(), PermissionEnvelope.of(permissions));
            var placement = StaffPlacement.assign(current.staff(), current.department(), position);
            return resolver.resolve(current.staff(), placement, position).permissionEnvelope();
        } catch (DataAccessException | IllegalArgumentException exception) {
            throw new StaffAuthorizationUnavailableException(exception);
        }
    }

    /** Keeps intermediate owner-local fields out of every public module contract. */
    private record CurrentPlacement(StaffProfile staff, Department department, UUID positionId,
            String code, String title, AuthorityBand authorityBand) {
    }
}

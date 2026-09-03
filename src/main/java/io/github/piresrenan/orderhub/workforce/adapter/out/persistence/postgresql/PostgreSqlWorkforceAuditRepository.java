package io.github.piresrenan.orderhub.workforce.adapter.out.persistence.postgresql;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.piresrenan.orderhub.workforce.application.model.WorkforceAuditEvidence;
import io.github.piresrenan.orderhub.workforce.application.model.WorkforceAuditState;
import io.github.piresrenan.orderhub.workforce.application.port.out.WorkforceAuditPersistenceException;
import io.github.piresrenan.orderhub.workforce.application.port.out.WorkforceAuditRepository;

/**
 * PostgreSQL append adapter for workforce audit evidence.
 *
 * <p>
 * This adapter deliberately owns no independent transaction boundary.
 * JdbcTemplate therefore participates in the caller's current transaction.
 * </p>
 */
public final class PostgreSqlWorkforceAuditRepository
        implements WorkforceAuditRepository {

    private final JdbcTemplate jdbcTemplate;

    public PostgreSqlWorkforceAuditRepository(
            JdbcTemplate jdbcTemplate) {

        if (jdbcTemplate == null) {
            throw new IllegalArgumentException(
                    "JdbcTemplate is required");
        }

        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public void append(
            WorkforceAuditEvidence evidence) {

        if (evidence == null) {
            throw new IllegalArgumentException(
                    "Workforce audit evidence is required");
        }

        var before = evidence.beforeState();
        var after = evidence.afterState();

        try {
            jdbcTemplate.update(
                    """
                    INSERT INTO workforce.audit_events (
                        audit_event_id,
                        tenant_id,
                        actor_staff_id,
                        affected_staff_id,
                        action_type,
                        outcome,
                        reason_code,
                        correlation_id,
                        before_status,
                        after_status,
                        before_department_id,
                        after_department_id,
                        before_position_id,
                        after_position_id,
                        before_supervisor_staff_id,
                        after_supervisor_staff_id,
                        before_authority_band,
                        after_authority_band
                    )
                    VALUES (
                        ?, ?, ?, ?, ?, ?, ?, ?,
                        ?, ?, ?, ?, ?, ?, ?, ?, ?, ?
                    )
                    """,
                    evidence.auditEventId(),
                    evidence.tenantId(),
                    evidence.actorStaffId(),
                    evidence.affectedStaffId(),
                    evidence.actionType().name(),
                    evidence.outcome().name(),
                    evidence.reasonCode(),
                    evidence.correlationId(),
                    statusName(before),
                    statusName(after),
                    before.departmentId(),
                    after.departmentId(),
                    before.positionId(),
                    after.positionId(),
                    before.supervisorStaffId(),
                    after.supervisorStaffId(),
                    authorityBandName(before),
                    authorityBandName(after));

        } catch (DataAccessException exception) {
            throw new WorkforceAuditPersistenceException(
                    "Failed to append workforce audit evidence",
                    exception);
        }
    }

    private static String statusName(
            WorkforceAuditState state) {

        return state.status() == null
                ? null
                : state.status().name();
    }

    private static String authorityBandName(
            WorkforceAuditState state) {

        return state.authorityBand() == null
                ? null
                : state.authorityBand().name();
    }
}

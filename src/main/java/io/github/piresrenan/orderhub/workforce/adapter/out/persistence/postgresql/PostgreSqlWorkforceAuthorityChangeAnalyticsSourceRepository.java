package io.github.piresrenan.orderhub.workforce.adapter.out.persistence.postgresql;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.util.Optional;
import java.util.UUID;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.piresrenan.orderhub.workforce.application.model.WorkforceAuditActionType;
import io.github.piresrenan.orderhub.workforce.application.model.WorkforceAuditOutcome;
import io.github.piresrenan.orderhub.workforce.application.port.in.WorkforceAuthorityChangeAnalyticsSource;
import io.github.piresrenan.orderhub.workforce.application.port.out.WorkforceAuditPersistenceException;
import io.github.piresrenan.orderhub.workforce.application.port.out.WorkforceAuthorityChangeAnalyticsSourceRepository;

/**
 * PostgreSQL read adapter for the bounded workforce authority-change analytical
 * source.
 *
 * <p>
 * This adapter deliberately owns no transaction boundary. JdbcTemplate
 * therefore participates in the transaction established by the caller, if any.
 * </p>
 *
 * <p>
 * The statement selects only the columns the bounded contract exposes, so
 * correlation identity and organizational before/after state are never read out
 * of the audit relation for this purpose.
 * </p>
 */
public final class PostgreSqlWorkforceAuthorityChangeAnalyticsSourceRepository
        implements WorkforceAuthorityChangeAnalyticsSourceRepository {

    /**
     * Reads one Tenant-scoped audit event.
     *
     * <p>
     * Tenant is part of the predicate rather than a check applied to a row that
     * was already read, so an event belonging to another Tenant is never
     * loaded and the query cannot reveal that it exists.
     * </p>
     */
    private static final String FIND_BY_TENANT_AND_AUDIT_EVENT =
            """
            SELECT
                tenant_id,
                audit_event_id,
                actor_staff_id,
                affected_staff_id,
                action_type,
                outcome,
                reason_code,
                occurred_at
            FROM workforce.audit_events
            WHERE tenant_id = ?
              AND audit_event_id = ?
            """;

    private final JdbcTemplate jdbcTemplate;

    public PostgreSqlWorkforceAuthorityChangeAnalyticsSourceRepository(
            JdbcTemplate jdbcTemplate) {

        if (jdbcTemplate == null) {
            throw new IllegalArgumentException(
                    "JdbcTemplate is required");
        }

        this.jdbcTemplate = jdbcTemplate;
    }

    @Override
    public Optional<WorkforceAuthorityChangeAnalyticsSource> findByTenantAndAuditEvent(
            UUID tenantId,
            UUID auditEventId) {

        try {
            var sources =
                    jdbcTemplate.query(
                            FIND_BY_TENANT_AND_AUDIT_EVENT,
                            (resultSet, rowNumber) ->
                                    mapSource(
                                            resultSet),
                            tenantId,
                            auditEventId);

            return sources.stream()
                    .findFirst();

        } catch (DataAccessException exception) {
            throw new WorkforceAuditPersistenceException(
                    "Failed to read workforce authority-change analytical"
                            + " source",
                    exception);
        }
    }

    /**
     * Maps one audit row onto the bounded contract.
     *
     * <p>
     * The occurrence time is the value the relation already stores. Nothing
     * here substitutes a clock, a processing time or a retry time for it.
     * </p>
     */
    private static WorkforceAuthorityChangeAnalyticsSource mapSource(
            ResultSet resultSet)
            throws SQLException {

        return new WorkforceAuthorityChangeAnalyticsSource(
                resultSet.getObject(
                        "tenant_id",
                        UUID.class),
                resultSet.getObject(
                        "audit_event_id",
                        UUID.class),
                resultSet.getObject(
                        "actor_staff_id",
                        UUID.class),
                resultSet.getObject(
                        "affected_staff_id",
                        UUID.class),
                WorkforceAuditActionType.valueOf(
                        resultSet.getString(
                                "action_type")),
                WorkforceAuditOutcome.valueOf(
                        resultSet.getString(
                                "outcome")),
                resultSet.getString(
                        "reason_code"),
                resultSet.getTimestamp(
                                "occurred_at")
                        .toInstant());
    }
}

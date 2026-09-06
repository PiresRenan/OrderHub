package io.github.piresrenan.orderhub.authorization.adapter.out.persistence.postgresql;

import java.util.Objects;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.piresrenan.orderhub.authorization.application.model.AdministrativeGrantAuditEvidence;
import io.github.piresrenan.orderhub.authorization.application.port.out.AdministrativeGrantAuditRepository;
import io.github.piresrenan.orderhub.authorization.application.port.out.AuthorizationPersistenceException;

public final class PostgreSqlAdministrativeGrantAuditRepository
        implements AdministrativeGrantAuditRepository {

    private static final String APPEND_SQL = """
            INSERT INTO access_control.administrative_grant_audit_events (
                audit_event_id,
                actor_user_id,
                target_user_id,
                scope_type,
                scope_id,
                permission_code,
                action_type,
                outcome,
                correlation_id,
                before_granted,
                after_granted
            )
            VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbcTemplate;

    public PostgreSqlAdministrativeGrantAuditRepository(
            JdbcTemplate jdbcTemplate) {

        this.jdbcTemplate =
                Objects.requireNonNull(
                        jdbcTemplate,
                        "jdbcTemplate");
    }

    @Override
    public void append(
            AdministrativeGrantAuditEvidence evidence) {

        Objects.requireNonNull(
                evidence,
                "evidence");

        try {
            jdbcTemplate.update(
                    APPEND_SQL,
                    evidence.auditEventId(),
                    evidence.actorUserId(),
                    evidence.targetUserId(),
                    evidence.scope()
                            .type()
                            .name(),
                    evidence.scope()
                            .scopeId(),
                    evidence.permission()
                            .name(),
                    evidence.action()
                            .name(),
                    evidence.outcome()
                            .name(),
                    evidence.correlationId(),
                    evidence.beforeGranted(),
                    evidence.afterGranted());

        } catch (DataAccessException exception) {

            throw new AuthorizationPersistenceException(
                    exception);
        }
    }
}

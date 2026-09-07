package io.github.piresrenan.orderhub.tenants.adapter.out.persistence.postgresql;

import java.sql.Types;
import java.util.Objects;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.piresrenan.orderhub.tenants.application.model.TenantAdministrativeAuditEvidence;
import io.github.piresrenan.orderhub.tenants.application.port.out.TenantAdministrativeAuditRepository;
import io.github.piresrenan.orderhub.tenants.application.port.out.TenantPersistenceException;

public final class PostgreSqlTenantAdministrativeAuditRepository
        implements TenantAdministrativeAuditRepository {

    private static final String INSERT_SQL = """
            INSERT INTO tenants.administrative_audit_events (
                audit_event_id, actor_user_id, tenant_id, action_type, outcome,
                before_status, after_status, correlation_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbc;

    public PostgreSqlTenantAdministrativeAuditRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public void append(TenantAdministrativeAuditEvidence evidence) {
        Objects.requireNonNull(evidence, "evidence");
        try {
            jdbc.update(
                    INSERT_SQL,
                    new Object[] {
                        evidence.auditEventId(), evidence.actorUserId(),
                        evidence.tenantId(), evidence.action().name(),
                        evidence.outcome().name(), name(evidence.beforeStatus()),
                        evidence.afterStatus().name(), evidence.correlationId()
                    },
                    new int[] {
                        Types.OTHER, Types.OTHER, Types.OTHER, Types.VARCHAR,
                        Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.OTHER
                    });
        } catch (DataAccessException exception) {
            throw new TenantPersistenceException(exception);
        }
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }
}

package io.github.piresrenan.orderhub.organizations.adapter.out.persistence.postgresql;

import java.sql.Types;
import java.util.Objects;

import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;

import io.github.piresrenan.orderhub.organizations.application.model.OrganizationAdministrativeAuditEvidence;
import io.github.piresrenan.orderhub.organizations.application.port.out.OrganizationAdministrativeAuditRepository;
import io.github.piresrenan.orderhub.organizations.application.port.out.OrganizationPersistenceException;

public final class PostgreSqlOrganizationAdministrativeAuditRepository
        implements OrganizationAdministrativeAuditRepository {

    private static final String INSERT_SQL = """
            INSERT INTO organizations.administrative_audit_events (
                audit_event_id, actor_user_id, organization_id, tenant_id,
                action_type, outcome, before_organization_status,
                after_organization_status, before_placement_organization_id,
                after_placement_organization_id, correlation_id
            ) VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
            """;

    private final JdbcTemplate jdbc;

    public PostgreSqlOrganizationAdministrativeAuditRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    @Override
    public void append(OrganizationAdministrativeAuditEvidence evidence) {
        Objects.requireNonNull(evidence, "evidence");
        try {
            jdbc.update(
                    INSERT_SQL,
                    new Object[] {
                        evidence.auditEventId(), evidence.actorUserId(),
                        evidence.organizationId(), evidence.tenantId(),
                        evidence.action().name(), evidence.outcome().name(),
                        name(evidence.beforeOrganizationStatus()),
                        name(evidence.afterOrganizationStatus()),
                        evidence.beforePlacementOrganizationId(),
                        evidence.afterPlacementOrganizationId(),
                        evidence.correlationId()
                    },
                    new int[] {
                        Types.OTHER, Types.OTHER, Types.OTHER, Types.OTHER,
                        Types.VARCHAR, Types.VARCHAR, Types.VARCHAR, Types.VARCHAR,
                        Types.OTHER, Types.OTHER, Types.OTHER
                    });
        } catch (DataAccessException exception) {
            throw new OrganizationPersistenceException(exception);
        }
    }

    private static String name(Enum<?> value) {
        return value == null ? null : value.name();
    }
}

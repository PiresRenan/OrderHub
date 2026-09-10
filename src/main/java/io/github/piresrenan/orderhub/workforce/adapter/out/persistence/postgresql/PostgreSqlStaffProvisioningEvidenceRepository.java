package io.github.piresrenan.orderhub.workforce.adapter.out.persistence.postgresql;

import java.util.Objects;
import java.util.UUID;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import io.github.piresrenan.orderhub.workforce.application.model.StaffProvisioningEvidence;
import io.github.piresrenan.orderhub.workforce.application.port.out.StaffProvisioningEvidenceRepository;
import io.github.piresrenan.orderhub.workforce.application.port.out.StaffProvisioningIntentPersistenceException;

/** Writes bounded provisioning evidence using the authoritative workforce connection. */
public final class PostgreSqlStaffProvisioningEvidenceRepository implements StaffProvisioningEvidenceRepository {
    private final JdbcTemplate jdbc;

    /** Uses the mutation's DataSource; no autonomous transaction is created. */
    public PostgreSqlStaffProvisioningEvidenceRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /** Fails before writing if the caller has not bound this DataSource to a transaction. */
    @Override
    public void append(StaffProvisioningEvidence evidence) {
        Objects.requireNonNull(evidence, "evidence");
        if (!TransactionSynchronizationManager.isActualTransactionActive()
                || !TransactionSynchronizationManager.hasResource(Objects.requireNonNull(jdbc.getDataSource(), "dataSource"))) {
            throw new IllegalStateException("Provisioning evidence requires its authoritative transaction");
        }
        try {
            jdbc.update("""
                    INSERT INTO workforce.provisioning_events
                        (event_id, tenant_id, intent_id, actor_user_id, subject_user_id, staff_id, action, correlation_id)
                    VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                    """, UUID.randomUUID(), evidence.tenantId(), evidence.intentId(), evidence.actorUserId(),
                    evidence.subjectUserId(), evidence.staffId(), evidence.action().name(), evidence.correlationId());
        } catch (DataAccessException exception) {
            throw new StaffProvisioningIntentPersistenceException("Provisioning evidence is unavailable", exception);
        }
    }

    /** Reads immutable issuance attribution without interpreting external identity or credentials. */
    @Override
    public boolean isColdStart(UUID tenantId, UUID intentId) {
        Objects.requireNonNull(tenantId, "tenantId");
        Objects.requireNonNull(intentId, "intentId");
        try {
            return Boolean.TRUE.equals(jdbc.queryForObject("""
                    SELECT EXISTS (SELECT 1 FROM workforce.provisioning_events
                        WHERE tenant_id = ? AND intent_id = ? AND action = 'COLD_START_ISSUED')
                    """, Boolean.class, tenantId, intentId));
        } catch (DataAccessException exception) {
            throw new StaffProvisioningIntentPersistenceException("Provisioning evidence is unavailable", exception);
        }
    }
}

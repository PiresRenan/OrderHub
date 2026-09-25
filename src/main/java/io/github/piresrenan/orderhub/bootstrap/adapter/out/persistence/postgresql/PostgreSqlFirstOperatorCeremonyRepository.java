package io.github.piresrenan.orderhub.bootstrap.adapter.out.persistence.postgresql;

import java.util.Objects;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import io.github.piresrenan.orderhub.bootstrap.application.port.out.FirstOperatorCeremonyRepository;
import io.github.piresrenan.orderhub.bootstrap.application.port.out.FirstOperatorCeremonyState;

/**
 * PostgreSQL singleton ceremony state and evidence (V46).
 *
 * <p>{@code SELECT ... FOR UPDATE} on the single row is the global arbitration
 * point; the forward-only trigger and the unique success constraint make an
 * accidental second transition fail in the database even if this adapter
 * were bypassed. Every method refuses to run without the caller transaction
 * so no write can ever commit on its own.</p>
 */
public final class PostgreSqlFirstOperatorCeremonyRepository implements FirstOperatorCeremonyRepository {
    private static final String CEREMONY = "RETAINED_FIRST_OPERATOR_BOOTSTRAP";
    private final JdbcTemplate jdbc;

    /** Requires the shared JDBC access; construction performs no query. */
    public PostgreSqlFirstOperatorCeremonyRepository(JdbcTemplate jdbc) {
        this.jdbc = Objects.requireNonNull(jdbc, "jdbc");
    }

    /** Blocks competing ceremonies on the singleton row until this transaction ends. */
    @Override
    public FirstOperatorCeremonyState lock() {
        requireTransaction();
        return jdbc.queryForObject("""
                SELECT state, operation_id, operator_user_id, request_fingerprint FROM bootstrap.first_operator_ceremony
                WHERE ceremony = ? FOR UPDATE
                """, (row, index) -> new FirstOperatorCeremonyState("COMPLETED".equals(row.getString("state")),
                row.getObject("operation_id", UUID.class), row.getObject("operator_user_id", UUID.class),
                row.getString("request_fingerprint")), CEREMONY);
    }

    /** Appends the single success evidence row; a second one violates the unique constraint. */
    @Override
    public void appendCompletedEvidence(UUID eventId, UUID operationId, UUID operatorUserId) {
        requireTransaction();
        jdbc.update("""
                INSERT INTO bootstrap.first_operator_ceremony_events
                    (event_id, ceremony, operation_id, outcome, operator_user_id, granted_permission)
                VALUES (?, ?, ?, 'COMPLETED', ?, 'PLATFORM_TENANTS_MANAGE')
                """, eventId, CEREMONY, operationId, operatorUserId);
    }

    /** Closes the ceremony; the conditional update must change exactly the open singleton row. */
    @Override
    public void complete(UUID operationId, UUID operatorUserId, String requestFingerprint) {
        requireTransaction();
        var changed = jdbc.update("""
                UPDATE bootstrap.first_operator_ceremony
                SET state = 'COMPLETED', operation_id = ?, operator_user_id = ?, request_fingerprint = ?,
                    completed_at = CURRENT_TIMESTAMP
                WHERE ceremony = ? AND state = 'OPEN'
                """, operationId, operatorUserId, requestFingerprint, CEREMONY);
        if (changed != 1) {
            throw new IllegalStateException("First-operator ceremony was not open");
        }
    }

    /** Keeps every ceremony write inside the caller-owned transaction. */
    private static void requireTransaction() {
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException("First-operator ceremony requires the caller transaction");
        }
    }
}

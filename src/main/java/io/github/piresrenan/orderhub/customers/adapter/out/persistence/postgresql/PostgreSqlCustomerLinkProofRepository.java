package io.github.piresrenan.orderhub.customers.adapter.out.persistence.postgresql;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import io.github.piresrenan.orderhub.customers.application.port.in.linking.CustomerLinkUnavailableException;
import io.github.piresrenan.orderhub.customers.application.port.out.CustomerAccountBindingPersistenceException;
import io.github.piresrenan.orderhub.customers.application.port.out.CustomerLinkProofRepository;

/** Owner-local SQL only; row transitions, binding and evidence join the caller transaction. */
public final class PostgreSqlCustomerLinkProofRepository implements CustomerLinkProofRepository {
    private final JdbcTemplate jdbc;
    public PostgreSqlCustomerLinkProofRepository(JdbcTemplate jdbc) { this.jdbc = java.util.Objects.requireNonNull(jdbc); }

    @Override public Creation create(UUID actor, UUID tenant, UUID customer, UUID operation, UUID correlation, byte[] digest) {
        return guarded(() -> {
            // A completed operation can be replayed without competing for its mutable proof row.
            var replay = replay(actor, tenant, customer, operation);
            if (replay.isPresent()) { return new Creation(replay.get(), false); }
            var inserted = jdbc.query("""
                    INSERT INTO customers.account_link_proofs
                      (proof_id, tenant_id, customer_id, issued_by_user_id, operation_id, correlation_id,
                       credential_digest, created_at, expires_at)
                    SELECT ?, tenant_id, customer_id, ?, ?, ?, ?, statement_timestamp(), statement_timestamp() + INTERVAL '15 minutes'
                    FROM customers.customer_profiles WHERE tenant_id = ? AND customer_id = ?
                    ON CONFLICT (tenant_id, operation_id) DO NOTHING RETURNING *
                    """, this::map, UUID.randomUUID(), actor, operation, correlation, digest, tenant, customer);
            if (!inserted.isEmpty()) { return new Creation(inserted.getFirst(), true); }
            return new Creation(replay(actor, tenant, customer, operation).orElseThrow(CustomerLinkUnavailableException::new), false);
        });
    }

    private Optional<Proof> replay(UUID actor, UUID tenant, UUID customer, UUID operation) {
        var existing = jdbc.query("SELECT * FROM customers.account_link_proofs WHERE tenant_id = ? AND operation_id = ?", this::map, tenant, operation);
        if (existing.isEmpty()) { return Optional.empty(); }
        var proof = existing.getFirst();
        if (!proof.issuerUserId().equals(actor) || !proof.customerId().equals(customer)) { throw new CustomerLinkUnavailableException(); }
        return Optional.of(proof);
    }

    @Override public Optional<Proof> consume(UUID tenant, byte[] digest) {
        return guarded(() -> jdbc.query("""
                UPDATE customers.account_link_proofs SET consumed_at = clock_timestamp()
                WHERE tenant_id = ? AND credential_digest = ? AND consumed_at IS NULL AND cancelled_at IS NULL
                  AND expires_at > clock_timestamp() RETURNING *
                """, this::map, tenant, digest).stream().findFirst());
    }

    @Override public Optional<Proof> cancel(UUID tenant, UUID proof) {
        return guarded(() -> jdbc.query("""
                UPDATE customers.account_link_proofs SET cancelled_at = clock_timestamp()
                WHERE tenant_id = ? AND proof_id = ? AND consumed_at IS NULL AND cancelled_at IS NULL RETURNING *
                """, this::map, tenant, proof).stream().findFirst());
    }

    @Override public void bind(Proof proof, UUID user) {
        guarded(() -> jdbc.update("""
                INSERT INTO customers.customer_account_bindings (tenant_id, customer_id, user_id) VALUES (?, ?, ?)
                ON CONFLICT (tenant_id, customer_id, user_id) DO NOTHING
                """, proof.tenantId(), proof.customerId(), user));
    }

    @Override public void append(Proof proof, UUID actor, UUID subject, String action, UUID correlation) {
        guarded(() -> jdbc.update("""
                INSERT INTO customers.account_link_events
                  (event_id, tenant_id, customer_id, proof_id, actor_user_id, subject_user_id, action, correlation_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), proof.tenantId(), proof.customerId(), proof.proofId(), actor, subject, action, correlation));
    }

    private Proof map(ResultSet row, int ignored) throws SQLException {
        return new Proof(row.getObject("proof_id", UUID.class), row.getObject("tenant_id", UUID.class),
                row.getObject("customer_id", UUID.class), row.getObject("issued_by_user_id", UUID.class),
                row.getObject("correlation_id", UUID.class), row.getObject("expires_at", OffsetDateTime.class));
    }

    private <T> T guarded(Supplier<T> work) {
        if (!TransactionSynchronizationManager.isActualTransactionActive() || jdbc.getDataSource() == null
                || !TransactionSynchronizationManager.hasResource(jdbc.getDataSource())) {
            throw new IllegalStateException("Customer linking requires its database transaction");
        }
        try { return work.get(); }
        catch (DataAccessException exception) { throw new CustomerAccountBindingPersistenceException(exception); }
    }
}

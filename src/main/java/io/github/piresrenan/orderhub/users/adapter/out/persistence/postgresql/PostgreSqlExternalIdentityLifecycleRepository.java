package io.github.piresrenan.orderhub.users.adapter.out.persistence.postgresql;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;
import java.util.function.Supplier;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import io.github.piresrenan.orderhub.users.application.port.in.ExternalIdentityLifecycleUnavailableException;
import io.github.piresrenan.orderhub.users.application.port.out.ExternalIdentityBindingPersistenceException;
import io.github.piresrenan.orderhub.users.application.port.out.ExternalIdentityLifecycleRepository;

public final class PostgreSqlExternalIdentityLifecycleRepository implements ExternalIdentityLifecycleRepository {
    private final JdbcTemplate jdbc;
    public PostgreSqlExternalIdentityLifecycleRepository(JdbcTemplate jdbc) { this.jdbc = java.util.Objects.requireNonNull(jdbc); }

    @Override public Creation create(UUID user, UUID operation, UUID correlation, byte[] digest) {
        return guarded(() -> {
            var previous = replay(user, operation);
            if (previous.isPresent()) { return new Creation(previous.get(), false); }
            var inserted = jdbc.query("""
                    INSERT INTO users.external_identity_link_proofs
                      (proof_id, user_id, operation_id, correlation_id, credential_digest, created_at, expires_at)
                    VALUES (?, ?, ?, ?, ?, statement_timestamp(), statement_timestamp() + INTERVAL '15 minutes')
                    ON CONFLICT (user_id, operation_id) DO NOTHING RETURNING *
                    """, this::proof, UUID.randomUUID(), user, operation, correlation, digest);
            return inserted.isEmpty() ? new Creation(replay(user, operation).orElseThrow(ExternalIdentityLifecycleUnavailableException::new), false)
                    : new Creation(inserted.getFirst(), true);
        });
    }
    private Optional<Proof> replay(UUID user, UUID operation) {
        return jdbc.query("SELECT * FROM users.external_identity_link_proofs WHERE user_id = ? AND operation_id = ?", this::proof, user, operation).stream().findFirst();
    }
    @Override public Optional<Proof> consume(byte[] digest) {
        return guarded(() -> jdbc.query("""
                UPDATE users.external_identity_link_proofs SET consumed_at = clock_timestamp()
                WHERE credential_digest = ? AND consumed_at IS NULL AND cancelled_at IS NULL AND expires_at > clock_timestamp()
                RETURNING *
                """, this::proof, digest).stream().findFirst());
    }
    @Override public Optional<Proof> cancel(UUID user, UUID proofId) {
        return guarded(() -> jdbc.query("""
                UPDATE users.external_identity_link_proofs SET cancelled_at = clock_timestamp()
                WHERE user_id = ? AND proof_id = ? AND consumed_at IS NULL AND cancelled_at IS NULL RETURNING *
                """, this::proof, user, proofId).stream().findFirst());
    }
    @Override public void lockUser(UUID user) {
        guarded(() -> {
            // Compatible with legacy binding INSERT foreign-key KEY SHARE locks.
            if (jdbc.query("SELECT id FROM users.users WHERE id = ? FOR NO KEY UPDATE", (row, n) -> row.getObject(1, UUID.class), user).isEmpty()) {
                throw new ExternalIdentityLifecycleUnavailableException();
            }
            return null;
        });
    }
    @Override public List<Account> accounts(UUID user) {
        return guarded(() -> jdbc.query("SELECT binding_id, issuer, active FROM users.external_identity_bindings WHERE user_id = ? ORDER BY binding_id",
                (row, n) -> new Account(row.getObject("binding_id", UUID.class), row.getString("issuer"), row.getBoolean("active")), user));
    }
    @Override public Link link(UUID user, String issuer, String subject) {
        return guarded(() -> {
            var inserted = jdbc.query("""
                    INSERT INTO users.external_identity_bindings (issuer, subject, user_id)
                    VALUES (?, ?, ?) ON CONFLICT (issuer, subject) DO NOTHING RETURNING binding_id
                    """, (row, n) -> row.getObject(1, UUID.class), issuer, subject, user);
            if (!inserted.isEmpty()) { return new Link(inserted.getFirst(), true); }
            var existing = jdbc.queryForMap("SELECT binding_id, user_id, active FROM users.external_identity_bindings WHERE issuer = ? AND subject = ?", issuer, subject);
            if (!user.equals(existing.get("user_id"))) { throw new ExternalIdentityLifecycleUnavailableException(); }
            var binding = (UUID) existing.get("binding_id");
            if (Boolean.TRUE.equals(existing.get("active"))) { return new Link(binding, false); }
            jdbc.update("UPDATE users.external_identity_bindings SET active = TRUE WHERE binding_id = ? AND user_id = ?", binding, user);
            return new Link(binding, true);
        });
    }
    @Override public boolean unlink(UUID user, UUID binding) {
        return guarded(() -> jdbc.update("UPDATE users.external_identity_bindings SET active = FALSE WHERE user_id = ? AND binding_id = ? AND active", user, binding) == 1);
    }
    @Override public void append(UUID user, UUID binding, UUID proof, String action, boolean changed, UUID correlation) {
        guarded(() -> jdbc.update("""
                INSERT INTO users.external_identity_events (event_id, user_id, binding_id, proof_id, action, changed, correlation_id)
                VALUES (?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), user, binding, proof, action, changed, correlation));
    }
    private Proof proof(ResultSet row, int ignored) throws SQLException {
        return new Proof(row.getObject("proof_id", UUID.class), row.getObject("user_id", UUID.class),
                row.getObject("correlation_id", UUID.class), row.getObject("expires_at", OffsetDateTime.class));
    }
    private <T> T guarded(Supplier<T> work) {
        if (!TransactionSynchronizationManager.isActualTransactionActive() || jdbc.getDataSource() == null
                || !TransactionSynchronizationManager.hasResource(jdbc.getDataSource())) {
            throw new IllegalStateException("External identity lifecycle requires its database transaction");
        }
        try { return work.get(); }
        catch (DataAccessException exception) { throw new ExternalIdentityBindingPersistenceException(exception); }
    }
}

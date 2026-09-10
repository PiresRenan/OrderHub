package io.github.piresrenan.orderhub.users.adapter.out.persistence.postgresql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.dao.DataIntegrityViolationException;
import io.github.piresrenan.orderhub.support.PostgreSqlTestConfiguration;
import io.github.piresrenan.orderhub.users.application.port.in.ResolveExternalIdentityQuery;
import io.github.piresrenan.orderhub.users.application.port.in.ResolveOrCreateExternalUserUseCase;

@SpringBootTest
@Import(PostgreSqlTestConfiguration.class)
class ExternalIdentityLifecycleSchemaTest {
    @Autowired private JdbcTemplate jdbc;
    @Autowired private ResolveOrCreateExternalUserUseCase users;

    @Test void bindingsHaveOpaqueLifecycleIdentityWithoutChangingTheirExactPairOwner() {
        var identity = new ResolveExternalIdentityQuery("https://synthetic-lifecycle-schema.test", UUID.randomUUID().toString());
        var user = users.resolveOrCreate(identity).userId();
        var binding = jdbc.queryForMap("SELECT binding_id, active, user_id FROM users.external_identity_bindings WHERE issuer = ? AND subject = ?", identity.issuer(), identity.subject());
        assertThat(binding.get("binding_id")).isInstanceOf(UUID.class);
        assertThat(binding.get("active")).isEqualTo(true);
        assertThat(binding.get("user_id")).isEqualTo(user);
    }

    @Test void linkingProofsPersistOnlyDigestAndInternalOwner() {
        assertThat(jdbc.queryForList("""
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = 'users' AND table_name = 'external_identity_link_proofs' ORDER BY ordinal_position
                """, String.class)).containsExactly("proof_id", "user_id", "operation_id", "correlation_id",
                        "credential_digest", "created_at", "expires_at", "consumed_at", "cancelled_at");
    }

    @Test void durableIdentityOwnerAndExactPairCannotBeRewritten() {
        var identity = new ResolveExternalIdentityQuery("https://synthetic-immutable-owner.test", UUID.randomUUID().toString());
        var user = users.resolveOrCreate(identity).userId();
        var binding = jdbc.queryForObject("SELECT binding_id FROM users.external_identity_bindings WHERE user_id = ?", UUID.class, user);
        assertThatThrownBy(() -> jdbc.update("UPDATE users.external_identity_bindings SET issuer = issuer || '/changed' WHERE binding_id = ?", binding)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE users.external_identity_bindings SET subject = subject || '-changed' WHERE binding_id = ?", binding)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE users.external_identity_bindings SET user_id = ? WHERE binding_id = ?", UUID.randomUUID(), binding)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("UPDATE users.external_identity_bindings SET binding_id = ? WHERE binding_id = ?", UUID.randomUUID(), binding)).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForObject("SELECT user_id FROM users.external_identity_bindings WHERE binding_id = ?", UUID.class, binding)).isEqualTo(user);
    }

    @Test void auditEvidenceIsAppendOnlyWithoutProviderIdentityOrCredentialMaterial() {
        var event = UUID.randomUUID();
        jdbc.update("INSERT INTO users.external_identity_events (event_id, user_id, proof_id, action, changed, correlation_id) VALUES (?, ?, ?, 'ISSUED', TRUE, ?)",
                event, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        assertThatThrownBy(() -> jdbc.update("UPDATE users.external_identity_events SET changed = FALSE WHERE event_id = ?", event)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM users.external_identity_events WHERE event_id = ?", event)).isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.execute("TRUNCATE users.external_identity_events")).isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForList("""
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = 'users' AND table_name = 'external_identity_events' ORDER BY ordinal_position
                """, String.class)).containsExactly("event_id", "user_id", "binding_id", "proof_id", "action", "changed", "correlation_id", "occurred_at");
    }
}

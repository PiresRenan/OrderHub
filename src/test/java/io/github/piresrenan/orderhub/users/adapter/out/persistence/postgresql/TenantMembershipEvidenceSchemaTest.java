package io.github.piresrenan.orderhub.users.adapter.out.persistence.postgresql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import io.github.piresrenan.orderhub.support.PostgreSqlTestConfiguration;

/** Membership transitions need owner-local attribution without erasing historical identities. */
@SpringBootTest
@Import(PostgreSqlTestConfiguration.class)
class TenantMembershipEvidenceSchemaTest {
    @Autowired private JdbcTemplate jdbc;

    @Test void durableEvidenceContainsOnlyInternalAttributionAndBoundedTransitionState() {
        assertThat(jdbc.queryForList("SELECT column_name FROM information_schema.columns WHERE table_schema = 'users' AND table_name = 'tenant_membership_events'", String.class))
                .containsExactlyInAnyOrder("event_id", "tenant_id", "actor_user_id", "subject_user_id", "action", "before_status", "after_status", "correlation_id", "occurred_at");
    }

    @Test void evidenceCannotBeChangedDeletedOrTruncated() {
        var event = java.util.UUID.randomUUID();
        jdbc.update("INSERT INTO users.tenant_membership_events VALUES (?, ?, ?, ?, 'SUSPEND', 'ACTIVE', 'SUSPENDED', ?, CURRENT_TIMESTAMP)",
                event, java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), java.util.UUID.randomUUID());
        assertThatThrownBy(() -> jdbc.update("UPDATE users.tenant_membership_events SET action = 'RECOVER' WHERE event_id = ?", event)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM users.tenant_membership_events WHERE event_id = ?", event)).isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> jdbc.execute("TRUNCATE users.tenant_membership_events")).isInstanceOf(org.springframework.dao.DataAccessException.class);
    }

    @Test void impossibleRecoveryAndSelfAttributedMutationCannotBeRecorded() {
        var actor = java.util.UUID.randomUUID();
        assertThatThrownBy(() -> jdbc.update("INSERT INTO users.tenant_membership_events VALUES (?, ?, ?, ?, 'RECOVER', 'TERMINATED', 'ACTIVE', ?, CURRENT_TIMESTAMP)",
                java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), actor, java.util.UUID.randomUUID(), java.util.UUID.randomUUID()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO users.tenant_membership_events VALUES (?, ?, ?, ?, 'SUSPEND', 'ACTIVE', 'SUSPENDED', ?, CURRENT_TIMESTAMP)",
                java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), actor, actor, java.util.UUID.randomUUID()))
                .isInstanceOf(org.springframework.dao.DataAccessException.class);
    }
}

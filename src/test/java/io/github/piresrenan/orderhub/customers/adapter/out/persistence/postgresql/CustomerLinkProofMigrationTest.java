package io.github.piresrenan.orderhub.customers.adapter.out.persistence.postgresql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;
import io.github.piresrenan.orderhub.support.PostgreSqlTestConfiguration;

/** Customer proof durability is independent of Staff placement and role state. */
@SpringBootTest
@Import(PostgreSqlTestConfiguration.class)
class CustomerLinkProofMigrationTest {
    @Autowired private JdbcTemplate jdbc;

    @Test void proofRequiresAnExistingCustomerInTheSameTenant() {
        var tenant = UUID.randomUUID();
        var customer = UUID.randomUUID();
        jdbc.update("INSERT INTO customers.customer_profiles VALUES (?, ?)", tenant, customer);
        assertThatThrownBy(() -> insert(UUID.randomUUID(), customer, new byte[32], "15 minutes"))
                .isInstanceOf(DataIntegrityViolationException.class);
        insert(tenant, customer, new byte[32], "15 minutes");
    }

    @Test void databaseBoundsDigestAndLifetime() {
        var tenant = UUID.randomUUID();
        var customer = UUID.randomUUID();
        jdbc.update("INSERT INTO customers.customer_profiles VALUES (?, ?)", tenant, customer);
        assertThatThrownBy(() -> insert(tenant, customer, new byte[31], "15 minutes"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert(tenant, customer, new byte[32], "25 hours"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> insert(tenant, customer, new byte[32], "-1 second"))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test void schemaContainsNoRawCredentialOrContactSelectors() {
        assertThat(jdbc.queryForList("""
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = 'customers' AND table_name = 'account_link_proofs'
                ORDER BY ordinal_position
                """, String.class)).containsExactly("proof_id", "tenant_id", "customer_id", "issued_by_user_id",
                        "operation_id", "correlation_id", "credential_digest", "created_at", "expires_at", "consumed_at", "cancelled_at");
    }

    @Test void evidenceIsAppendOnlyAndContainsOnlyInternalAttribution() {
        var event = UUID.randomUUID();
        jdbc.update("""
                INSERT INTO customers.account_link_events
                  (event_id, tenant_id, customer_id, proof_id, actor_user_id, action, correlation_id)
                VALUES (?, ?, ?, ?, ?, 'ISSUED', ?)
                """, event, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID());
        assertThatThrownBy(() -> jdbc.update("UPDATE customers.account_link_events SET action = 'CANCELLED' WHERE event_id = ?", event))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM customers.account_link_events WHERE event_id = ?", event))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.execute("TRUNCATE customers.account_link_events"))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThat(jdbc.queryForList("""
                SELECT column_name FROM information_schema.columns
                WHERE table_schema = 'customers' AND table_name = 'account_link_events' ORDER BY ordinal_position
                """, String.class)).containsExactly("event_id", "tenant_id", "customer_id", "proof_id", "actor_user_id",
                        "subject_user_id", "action", "correlation_id", "occurred_at");
    }

    private void insert(UUID tenant, UUID customer, byte[] digest, String lifetime) {
        jdbc.update("""
                INSERT INTO customers.account_link_proofs
                  (proof_id, tenant_id, customer_id, issued_by_user_id, operation_id, correlation_id,
                   credential_digest, created_at, expires_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, statement_timestamp(), statement_timestamp() + CAST(? AS interval))
                """, UUID.randomUUID(), tenant, customer, UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), digest, lifetime);
    }
}

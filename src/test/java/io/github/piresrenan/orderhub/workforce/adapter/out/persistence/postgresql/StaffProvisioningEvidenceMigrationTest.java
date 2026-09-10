package io.github.piresrenan.orderhub.workforce.adapter.out.persistence.postgresql;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/**
 * Why: existing Staff audit requires actorStaffId and cannot record Platform
 * cold-start or the internal User established by one consumed intent.
 * Covers: bounded owner-local issuance/consumption evidence and append-only storage.
 * Prevents: fabricating a Staff actor, losing provisioning attribution or rewriting history.
 */
@Testcontainers
class StaffProvisioningEvidenceMigrationTest {
    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(
            DockerImageName.parse("postgres:18.6-trixie@sha256:4ef4dbc939d61acea57712655ddb4b4ab27419c913f94cca0cd57cb3ea3c2280")
                    .asCompatibleSubstituteFor("postgres"));
    private static JdbcTemplate jdbc;

    @BeforeAll
    static void migrate() {
        var source = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(source).load().migrate();
        jdbc = new JdbcTemplate(source);
    }

    @Test
    void storesExactIntentActorAndResultWithoutInventingStaffActor() {
        var intent = UUID.randomUUID();
        append(intent, "COLD_START_ISSUED", null, null);
        append(intent, "CONSUMED", UUID.randomUUID(), UUID.randomUUID());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workforce.provisioning_events WHERE intent_id = ?",
                Integer.class, intent)).isEqualTo(2);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workforce.provisioning_events WHERE intent_id = ? AND occurred_at IS NOT NULL",
                Integer.class, intent)).isEqualTo(2);
    }

    @Test
    void cannotRewriteOrEraseProvisioningEvidence() {
        var intent = UUID.randomUUID();
        append(intent, "ISSUED", null, null);
        assertThatThrownBy(() -> jdbc.update("UPDATE workforce.provisioning_events SET action = 'CANCELLED' WHERE intent_id = ?", intent))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.update("DELETE FROM workforce.provisioning_events WHERE intent_id = ?", intent))
                .isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> jdbc.execute("TRUNCATE workforce.provisioning_events"))
                .isInstanceOf(DataAccessException.class);
    }

    @Test
    void rejectsFalseConsumptionAndConflictingIssuanceModes() {
        var intent = UUID.randomUUID();
        append(intent, "ISSUED", null, null);
        assertThatThrownBy(() -> append(intent, "COLD_START_ISSUED", null, null)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> append(UUID.randomUUID(), "CONSUMED", null, null)).isInstanceOf(DataAccessException.class);
        assertThatThrownBy(() -> append(UUID.randomUUID(), "ISSUED", UUID.randomUUID(), UUID.randomUUID()))
                .isInstanceOf(DataAccessException.class);
    }

    private void append(UUID intent, String action, UUID user, UUID staff) {
        jdbc.update("""
                INSERT INTO workforce.provisioning_events
                    (event_id, tenant_id, intent_id, actor_user_id, subject_user_id, staff_id, action, correlation_id)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?)
                """, UUID.randomUUID(), UUID.randomUUID(), intent, UUID.randomUUID(), user, staff, action, UUID.randomUUID());
    }
}

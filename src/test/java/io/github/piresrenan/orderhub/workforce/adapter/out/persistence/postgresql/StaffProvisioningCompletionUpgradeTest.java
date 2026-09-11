package io.github.piresrenan.orderhub.workforce.adapter.out.persistence.postgresql;

import static org.assertj.core.api.Assertions.assertThat;
import java.util.UUID;
import org.flywaydb.core.Flyway;
import org.junit.jupiter.api.Test;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.testcontainers.junit.jupiter.Container;
import org.testcontainers.junit.jupiter.Testcontainers;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

/** Verifies upgrade from published V37 preserves existing proofs and accepted migration checksums. */
/**
 * Why: Staff onboarding is a privileged one-time workflow rather than a caller-asserted identity.
 * Covers: The intent, issuance, consumption or composition boundary exercised by this suite.
 * Prevents: Replay corruption, authority bypass and incomplete atomic provisioning behavior.
 */
@Testcontainers
class StaffProvisioningCompletionUpgradeTest {
    @Container
    private static final PostgreSQLContainer POSTGRES = new PostgreSQLContainer(DockerImageName.parse(
            "postgres:18.6-trixie@sha256:4ef4dbc939d61acea57712655ddb4b4ab27419c913f94cca0cd57cb3ea3c2280")
            .asCompatibleSubstituteFor("postgres"))
            .withDatabaseName("orderhub_test").withUsername("orderhub_test").withPassword("synthetic-test-password");

    @Test
    void publishedIntentAndMigrationHistorySurviveCompletionUpgrade() {
        var dataSource = new DriverManagerDataSource(POSTGRES.getJdbcUrl(), POSTGRES.getUsername(), POSTGRES.getPassword());
        Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").target("37").load().migrate();
        var jdbc = new JdbcTemplate(dataSource);
        var tenant = UUID.randomUUID();
        var department = UUID.randomUUID();
        var position = UUID.randomUUID();
        var intent = UUID.randomUUID();
        jdbc.update("INSERT INTO workforce.departments VALUES (?, ?, 'UPGRADE', 'Synthetic upgrade')", department, tenant);
        jdbc.update("INSERT INTO workforce.job_positions VALUES (?, ?, 'UPGRADE', 'Synthetic upgrade', 'OPERATIONAL')", position, tenant);
        jdbc.update("""
                INSERT INTO workforce.staff_provisioning_intents
                    (intent_id, tenant_id, secret_digest, issued_by_user_id, department_id, position_id,
                     operation_id, request_fingerprint, expires_at, correlation_id)
                VALUES (?, ?, decode(repeat('31', 32), 'hex'), ?, ?, ?, ?, decode(repeat('42', 32), 'hex'),
                        clock_timestamp() + interval '1 hour', ?)
                """, intent, tenant, UUID.randomUUID(), department, position, UUID.randomUUID(), UUID.randomUUID());
        var acceptedHistory = jdbc.queryForList("SELECT version, checksum FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank");
        var proof = jdbc.queryForMap("SELECT intent_id, tenant_id, created_at, expires_at, consumed_at, cancelled_at, encode(secret_digest, 'hex') AS digest FROM workforce.staff_provisioning_intents WHERE intent_id = ?", intent);
        var latest = Flyway.configure().dataSource(dataSource).locations("classpath:db/migration").load();
        latest.migrate();
        latest.validate();
        assertThat(jdbc.queryForList("SELECT version, checksum FROM flyway_schema_history WHERE version IS NOT NULL ORDER BY installed_rank"))
                .containsAll(acceptedHistory);
        assertThat(jdbc.queryForMap("SELECT intent_id, tenant_id, created_at, expires_at, consumed_at, cancelled_at, encode(secret_digest, 'hex') AS digest FROM workforce.staff_provisioning_intents WHERE intent_id = ?", intent))
                .isEqualTo(proof);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM flyway_schema_history WHERE NOT success", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM workforce.provisioning_events", Integer.class)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM access_control.staff_provisioning_role_events", Integer.class)).isZero();
    }
}
